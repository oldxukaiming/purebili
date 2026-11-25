use crate::bili_client::{BiliClient, DashStreamMeta, DEFAULT_REFERER, DEFAULT_UA};
use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::{header, HeaderMap, HeaderValue, Method, StatusCode};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tauri::{AppHandle, Emitter, Manager};

#[derive(Clone)]
pub struct ProxyState {
    pub client: Arc<BiliClient>,
    pub app: AppHandle,
    pub port: u16,
}

#[derive(Deserialize)]
pub struct ProxyQuery {
    pub url: String,
}

#[derive(Deserialize)]
pub struct DashQuery {
    pub bvid: String,
    pub cid: u64,
    pub qn: u64,
}

#[derive(Deserialize)]
pub struct GeetestPageQuery {
    pub token: String,
    pub gt: String,
    pub challenge: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GeetestVerifyPayload {
    pub token: String,
    pub challenge: String,
    pub validate: String,
    pub seccode: String,
}

pub async fn start_proxy_server(client: Arc<BiliClient>, app: AppHandle) -> anyhow::Result<u16> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let port = listener.local_addr()?.port();
    client.set_proxy_port(port).await;

    let state = ProxyState {
        client,
        app,
        port,
    };

    let router = Router::new()
        .route("/proxy", get(handle_proxy).options(handle_cors_preflight))
        .route("/dash.mpd", get(handle_dash_mpd).options(handle_cors_preflight))
        .route("/geetest", get(handle_geetest_page))
        .route("/geetest_result", post(handle_geetest_result).options(handle_cors_preflight))
        .with_state(state);

    tokio::spawn(async move {
        if let Err(err) = axum::serve(listener, router).await {
            eprintln!("Local proxy server error: {}", err);
        }
    });

    Ok(port)
}

async fn handle_cors_preflight() -> Response {
    let mut headers = HeaderMap::new();
    add_cors_headers(&mut headers);
    (StatusCode::NO_CONTENT, headers).into_response()
}

fn add_cors_headers(headers: &mut HeaderMap) {
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_ORIGIN,
        HeaderValue::from_static("*"),
    );
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_METHODS,
        HeaderValue::from_static("GET, POST, OPTIONS"),
    );
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_HEADERS,
        HeaderValue::from_static("Range, Content-Type, Accept, Origin"),
    );
    headers.insert(
        header::ACCESS_CONTROL_EXPOSE_HEADERS,
        HeaderValue::from_static("Content-Length, Content-Range, Accept-Ranges, Content-Type"),
    );
}

fn is_allowed_bilibili_host(raw_url: &str) -> bool {
    let Ok(parsed) = reqwest::Url::parse(raw_url) else {
        return false;
    };
    let scheme = parsed.scheme();
    if scheme != "http" && scheme != "https" {
        return false;
    }
    let Some(host) = parsed.host_str() else {
        return false;
    };
    let h = host.to_ascii_lowercase();
    let allowed_suffixes = [
        "bilivideo.com",
        "bilivideo.cn",
        "hdslb.com",
        "bilibili.com",
        "akamaized.net",
        "szbdyd.com",
        "acgvideo.com",
        "biliapi.net",
    ];
    allowed_suffixes
        .iter()
        .any(|suffix| h == *suffix || h.ends_with(&format!(".{}", suffix)))
}

async fn handle_proxy(
    State(state): State<ProxyState>,
    method: Method,
    req_headers: HeaderMap,
    Query(q): Query<ProxyQuery>,
) -> Response {
    if method == Method::OPTIONS {
        return handle_cors_preflight().await;
    }

    if !is_allowed_bilibili_host(&q.url) {
        let parsed_host = reqwest::Url::parse(&q.url)
            .ok()
            .and_then(|u| u.host_str().map(|s| s.to_ascii_lowercase()));
        let allowed = match parsed_host {
            Some(h) => state.client.allowed_hosts.read().await.contains(&h),
            None => false,
        };
        if !allowed {
            return (StatusCode::FORBIDDEN, "Blocked non-Bilibili host").into_response();
        }
    }

    let mut out_headers = HeaderMap::new();
    out_headers.insert(header::USER_AGENT, HeaderValue::from_static(DEFAULT_UA));
    out_headers.insert(header::REFERER, HeaderValue::from_static(DEFAULT_REFERER));
    out_headers.insert(
        header::ORIGIN,
        HeaderValue::from_static("https://www.bilibili.com"),
    );

    if let Some(range) = req_headers.get(header::RANGE) {
        out_headers.insert(header::RANGE, range.clone());
    }

    let resp = match state.client.http.get(&q.url).headers(out_headers).send().await {
        Ok(r) => r,
        Err(e) => {
            return (
                StatusCode::BAD_GATEWAY,
                format!("Upstream request failed: {}", e),
            )
                .into_response();
        }
    };

    let status = StatusCode::from_u16(resp.status().as_u16()).unwrap_or(StatusCode::OK);
    let mut resp_headers = HeaderMap::new();
    add_cors_headers(&mut resp_headers);

    for key in [
        header::CONTENT_TYPE,
        header::CONTENT_LENGTH,
        header::CONTENT_RANGE,
        header::ACCEPT_RANGES,
        header::CACHE_CONTROL,
    ] {
        if let Some(val) = resp.headers().get(&key) {
            resp_headers.insert(key, val.clone());
        }
    }

    let stream = resp.bytes_stream();
    let body = Body::from_stream(stream);
    (status, resp_headers, body).into_response()
}

fn xml_escape(input: &str) -> String {
    input
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

fn select_compatible_videos(videos: &[DashStreamMeta], preferred_qn: u64) -> Vec<DashStreamMeta> {
    // Prefer H.264 (avc1) for broadest WebView2 / hardware decoder compatibility
    let mut by_qn: HashMap<u64, DashStreamMeta> = HashMap::new();
    for v in videos {
        let is_avc = v.codecs.starts_with("avc");
        match by_qn.get(&v.id) {
            None => {
                by_qn.insert(v.id, v.clone());
            }
            Some(existing) => {
                let existing_avc = existing.codecs.starts_with("avc");
                if is_avc && !existing_avc {
                    by_qn.insert(v.id, v.clone());
                }
            }
        }
    }

    let mut list: Vec<DashStreamMeta> = by_qn.into_values().collect();
    // Put preferred_qn first, or filter to preferred_qn if present so dash.js starts directly at requested quality
    if let Some(exact) = list.iter().find(|v| v.id == preferred_qn).cloned() {
        return vec![exact];
    }
    list.sort_by(|a, b| b.id.cmp(&a.id));
    list
}

async fn handle_dash_mpd(State(state): State<ProxyState>, Query(q): Query<DashQuery>) -> Response {
    let cache_key = format!("{}_{}_{}", q.bvid, q.cid, q.qn);
    let cached_opt = state.client.dash_cache.read().await.get(&cache_key).cloned();
    let Some(cached) = cached_opt else {
        return (StatusCode::NOT_FOUND, "DASH manifest expired or not found").into_response();
    };

    let videos = select_compatible_videos(&cached.videos, q.qn);
    let best_audio = cached
        .audios
        .iter()
        .max_by_key(|a| a.bandwidth)
        .cloned();

    let mut video_reps = String::new();
    for (idx, v) in videos.iter().enumerate() {
        let proxied = format!(
            "http://127.0.0.1:{}/proxy?url={}",
            state.port,
            urlencoding::encode(&v.base_url)
        );
        video_reps.push_str(&format!(
            r#"      <Representation id="v_{}_{}" bandwidth="{}" codecs="{}" mimeType="{}" width="{}" height="{}" frameRate="{}">
        <BaseURL>{}</BaseURL>
        <SegmentBase indexRange="{}">
          <Initialization range="{}"/>
        </SegmentBase>
      </Representation>
"#,
            v.id,
            idx,
            v.bandwidth.max(100_000),
            xml_escape(&v.codecs),
            xml_escape(&v.mime_type),
            v.width.max(640),
            v.height.max(360),
            xml_escape(&v.frame_rate),
            xml_escape(&proxied),
            xml_escape(&v.index_range),
            xml_escape(&v.init_range),
        ));
    }

    let mut audio_reps = String::new();
    if let Some(a) = best_audio {
        let proxied = format!(
            "http://127.0.0.1:{}/proxy?url={}",
            state.port,
            urlencoding::encode(&a.base_url)
        );
        audio_reps.push_str(&format!(
            r#"      <Representation id="a_{}" bandwidth="{}" codecs="{}" mimeType="{}">
        <BaseURL>{}</BaseURL>
        <SegmentBase indexRange="{}">
          <Initialization range="{}"/>
        </SegmentBase>
      </Representation>
"#,
            a.id,
            a.bandwidth.max(64_000),
            xml_escape(&a.codecs),
            xml_escape(&a.mime_type),
            xml_escape(&proxied),
            xml_escape(&a.index_range),
            xml_escape(&a.init_range),
        ));
    }

    let duration_s = cached.duration.max(1);
    let mpd = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT{duration_s}S" minBufferTime="PT1.5S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
  <Period id="0" start="PT0S">
    <AdaptationSet id="1" contentType="video" segmentAlignment="true" subsegmentAlignment="true">
{video_reps}    </AdaptationSet>
    <AdaptationSet id="2" contentType="audio" segmentAlignment="true" subsegmentAlignment="true">
{audio_reps}    </AdaptationSet>
  </Period>
</MPD>"#
    );

    let mut headers = HeaderMap::new();
    add_cors_headers(&mut headers);
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/dash+xml; charset=utf-8"),
    );
    (StatusCode::OK, headers, mpd).into_response()
}

async fn handle_geetest_page(Query(q): Query<GeetestPageQuery>) -> Html<String> {
    let token_json = serde_json::to_string(&q.token).unwrap_or_else(|_| "\"\"".into());
    let gt_json = serde_json::to_string(&q.gt).unwrap_or_else(|_| "\"\"".into());
    let challenge_json = serde_json::to_string(&q.challenge).unwrap_or_else(|_| "\"\"".into());

    let html = format!(
        r#"<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Bilibili 安全验证</title>
  <style>
    body {{
      margin: 0;
      padding: 24px;
      font-family: "Microsoft YaHei", system-ui, sans-serif;
      background: #12141a;
      color: #f3f4f6;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: calc(100vh - 48px);
    }}
    .card {{
      background: #1b1f29;
      border: 1px solid #2d3343;
      border-radius: 12px;
      padding: 24px;
      width: 340px;
      text-align: center;
    }}
    h3 {{ margin: 0 0 8px 0; font-size: 18px; }}
    p {{ margin: 0 0 18px 0; font-size: 13px; color: #9ca3af; }}
    #captcha-box {{ min-height: 46px; display: flex; justify-content: center; }}
    #status {{ margin-top: 14px; font-size: 13px; color: #fb7299; }}
  </style>
  <script src="https://static.geetest.com/static/js/gt.0.4.9.js"></script>
</head>
<body>
  <div class="card">
    <h3>请完成官方安全验证</h3>
    <p>验证完成后将自动继续密码登录</p>
    <div id="captcha-box">正在加载极验组件...</div>
    <div id="status"></div>
  </div>
  <script>
    const token = {token_json};
    const gt = {gt_json};
    const challenge = {challenge_json};
    const statusEl = document.getElementById('status');
    const boxEl = document.getElementById('captcha-box');

    if (typeof window.initGeetest !== 'function') {{
      boxEl.textContent = '加载极验脚本失败，请检查网络连接或改用扫码登录。';
    }} else {{
      window.initGeetest({{
        gt: gt,
        challenge: challenge,
        offline: false,
        new_captcha: true,
        product: 'embed',
        width: '300px'
      }}, function(captchaObj) {{
        boxEl.innerHTML = '';
        captchaObj.appendTo('#captcha-box');
        captchaObj.onSuccess(async function() {{
          const result = captchaObj.getValidate();
          statusEl.textContent = '验证完成，正在提交登录...';
          await fetch('/geetest_result', {{
            method: 'POST',
            headers: {{ 'Content-Type': 'application/json' }},
            body: JSON.stringify({{
              token: token,
              challenge: result.geetest_challenge,
              validate: result.geetest_validate,
              seccode: result.geetest_seccode
            }})
          }});
        }});
        captchaObj.onError(function() {{
          statusEl.textContent = '验证组件出错，请关闭重试或改用扫码登录。';
        }});
      }});
    }}
  </script>
</body>
</html>"#
    );

    Html(html)
}

async fn handle_geetest_result(
    State(state): State<ProxyState>,
    Json(payload): Json<GeetestVerifyPayload>,
) -> impl IntoResponse {
    let _ = state.app.emit("geetest-verified", payload);
    if let Some(win) = state.app.get_webview_window("geetest") {
        let _ = win.close();
    }
    Json(serde_json::json!({ "ok": true }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_allowed_bilibili_hosts() {
        assert!(is_allowed_bilibili_host("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1.m4s"));
        assert!(is_allowed_bilibili_host("https://i0.hdslb.com/bfs/archive/abc.jpg"));
        assert!(is_allowed_bilibili_host("https://www.bilibili.com/video/BV1xx"));
        assert!(!is_allowed_bilibili_host("https://evil.example.com/test.mp4"));
        assert!(!is_allowed_bilibili_host("file:///C:/Windows/System32/drivers/etc/hosts"));
    }

    #[test]
    fn test_xml_escape() {
        assert_eq!(
            xml_escape("http://127.0.0.1:8080/proxy?a=1&b=\"2\""),
            "http://127.0.0.1:8080/proxy?a=1&amp;b=&quot;2&quot;"
        );
    }

    #[test]
    fn test_select_compatible_videos_prefers_avc() {
        let streams = vec![
            DashStreamMeta {
                id: 80,
                base_url: "https://a.bilivideo.com/hevc.m4s".into(),
                bandwidth: 1000,
                mime_type: "video/mp4".into(),
                codecs: "hev1.1.6.L120.90".into(),
                width: 1920,
                height: 1080,
                frame_rate: "30".into(),
                init_range: "0-10".into(),
                index_range: "11-20".into(),
            },
            DashStreamMeta {
                id: 80,
                base_url: "https://a.bilivideo.com/avc.m4s".into(),
                bandwidth: 1200,
                mime_type: "video/mp4".into(),
                codecs: "avc1.640032".into(),
                width: 1920,
                height: 1080,
                frame_rate: "30".into(),
                init_range: "0-10".into(),
                index_range: "11-20".into(),
            },
        ];
        let picked = select_compatible_videos(&streams, 80);
        assert_eq!(picked.len(), 1);
        assert_eq!(picked[0].codecs, "avc1.640032");
    }
}

