use crate::bili_client::{BiliClient, DEFAULT_REFERER, DEFAULT_UA};
use anyhow::{anyhow, Result};
use axum::body::Body;
use axum::extract::{Path, State};
use axum::http::{header, HeaderMap, HeaderValue, Method, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, UdpSocket};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::RwLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DlnaDevice {
    pub id: String,
    pub name: String,
    pub host: String,
    pub location: String,
    pub control_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CastResult {
    pub cast_url: String,
}

#[derive(Default)]
struct CastStreamState {
    token: String,
    target_url: String,
    port: u16,
}

pub struct DlnaManager {
    client: Arc<BiliClient>,
    cast_state: Arc<RwLock<CastStreamState>>,
}

impl DlnaManager {
    pub fn new(client: Arc<BiliClient>) -> Self {
        Self {
            client,
            cast_state: Arc::new(RwLock::new(CastStreamState::default())),
        }
    }

    async fn ensure_cast_server(&self) -> Result<u16> {
        {
            let guard = self.cast_state.read().await;
            if guard.port > 0 {
                return Ok(guard.port);
            }
        }
        let listener = tokio::net::TcpListener::bind("0.0.0.0:0").await?;
        let port = listener.local_addr()?.port();
        {
            let mut guard = self.cast_state.write().await;
            guard.port = port;
        }
        let client = Arc::clone(&self.client);
        let state = Arc::clone(&self.cast_state);
        let router = Router::new()
            .route("/cast/:file", get(handle_cast_stream))
            .with_state((client, state));

        tokio::spawn(async move {
            let _ = axum::serve(listener, router).await;
        });
        Ok(port)
    }

    pub async fn discover_devices(&self) -> Result<Vec<DlnaDevice>> {
        let locations = tokio::task::spawn_blocking(|| ssdp_search(Duration::from_millis(2200)))
            .await
            .map_err(|e| anyhow!(e.to_string()))?;

        let mut devices = Vec::new();
        let mut seen_controls = HashSet::new();
        for loc in locations {
            if let Ok(resp) = self
                .client
                .http
                .get(&loc)
                .timeout(Duration::from_secs(2))
                .send()
                .await
            {
                if let Ok(xml) = resp.text().await {
                    if let Some(dev) = parse_dlna_device_xml(&xml, &loc) {
                        if seen_controls.insert(dev.control_url.clone()) {
                            devices.push(dev);
                        }
                    }
                }
            }
        }
        Ok(devices)
    }

    pub async fn cast_video(
        &self,
        control_url: &str,
        bvid: &str,
        cid: u64,
        qn: u64,
        title: &str,
    ) -> Result<CastResult> {
        let mp4_url = self.client.get_raw_mp4_url(bvid, cid, qn).await?;
        let port = self.ensure_cast_server().await?;

        let mut rnd = [0u8; 16];
        rand::thread_rng().fill_bytes(&mut rnd);
        let token: String = rnd.iter().map(|b| format!("{:02x}", b)).collect();
        {
            let mut guard = self.cast_state.write().await;
            guard.token = token.clone();
            guard.target_url = mp4_url;
        }

        let parsed = reqwest::Url::parse(control_url)?;
        let host = parsed
            .host_str()
            .ok_or_else(|| anyhow!("无效的投屏设备地址"))?;
        let local_ip = get_local_ip_for_target(host);
        let cast_url = format!("http://{}:{}/cast/{}.mp4", local_ip, port, token);

        let didl = format!(
            r#"<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>{}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/mp4:*">{}</res></item></DIDL-Lite>"#,
            xml_escape(title),
            xml_escape(&cast_url)
        );

        let set_body = format!(
            "<InstanceID>0</InstanceID><CurrentURI>{}</CurrentURI><CurrentURIMetaData>{}</CurrentURIMetaData>",
            xml_escape(&cast_url),
            xml_escape(&didl)
        );
        self.send_soap(control_url, "SetAVTransportURI", &set_body)
            .await?;
        self.send_soap(control_url, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            .await?;

        Ok(CastResult { cast_url })
    }

    pub async fn control_device(&self, control_url: &str, command: &str) -> Result<()> {
        match command {
            "play" => {
                self.send_soap(control_url, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                    .await
            }
            "pause" => {
                self.send_soap(control_url, "Pause", "<InstanceID>0</InstanceID>")
                    .await
            }
            "stop" => {
                let res = self
                    .send_soap(control_url, "Stop", "<InstanceID>0</InstanceID>")
                    .await;
                let mut guard = self.cast_state.write().await;
                guard.token.clear();
                guard.target_url.clear();
                res
            }
            _ => Err(anyhow!("不支持的控制指令")),
        }
    }

    async fn send_soap(&self, control_url: &str, action: &str, inner_xml: &str) -> Result<()> {
        let envelope = format!(
            r#"<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:{action} xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      {inner_xml}
    </u:{action}>
  </s:Body>
</s:Envelope>"#
        );

        let resp = self
            .client
            .http
            .post(control_url)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header(
                "SOAPAction",
                format!("\"urn:schemas-upnp-org:service:AVTransport:1#{}\"", action),
            )
            .body(envelope)
            .send()
            .await?;

        if !resp.status().is_success() {
            return Err(anyhow!(
                "DLNA {} 失败 (HTTP {})",
                action,
                resp.status().as_u16()
            ));
        }
        Ok(())
    }
}

async fn handle_cast_stream(
    State((client, cast_state)): State<(Arc<BiliClient>, Arc<RwLock<CastStreamState>>)>,
    Path(file): Path<String>,
    method: Method,
    req_headers: HeaderMap,
) -> Response {
    let (expected_token, target_url) = {
        let guard = cast_state.read().await;
        (guard.token.clone(), guard.target_url.clone())
    };
    if expected_token.is_empty()
        || target_url.is_empty()
        || file != format!("{}.mp4", expected_token)
    {
        return (StatusCode::NOT_FOUND, "Not found").into_response();
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

    let resp = match client.http.get(&target_url).headers(out_headers).send().await {
        Ok(r) => r,
        Err(e) => return (StatusCode::BAD_GATEWAY, e.to_string()).into_response(),
    };

    let status = StatusCode::from_u16(resp.status().as_u16()).unwrap_or(StatusCode::OK);
    let mut resp_headers = HeaderMap::new();
    resp_headers.insert(
        header::ACCESS_CONTROL_ALLOW_ORIGIN,
        HeaderValue::from_static("*"),
    );
    for key in [
        header::CONTENT_TYPE,
        header::CONTENT_LENGTH,
        header::CONTENT_RANGE,
        header::ACCEPT_RANGES,
    ] {
        if let Some(v) = resp.headers().get(&key) {
            resp_headers.insert(key, v.clone());
        }
    }
    if !resp_headers.contains_key(header::ACCEPT_RANGES) {
        resp_headers.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    }

    if method == Method::HEAD {
        return (status, resp_headers).into_response();
    }
    let body = Body::from_stream(resp.bytes_stream());
    (status, resp_headers, body).into_response()
}

fn ssdp_search(timeout: Duration) -> Vec<String> {
    let mut locations = HashSet::new();
    let Ok(sock) = UdpSocket::bind("0.0.0.0:0") else {
        return Vec::new();
    };
    let _ = sock.set_read_timeout(Some(Duration::from_millis(300)));
    let _ = sock.set_broadcast(true);

    for st in [
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
    ] {
        let msg = format!(
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {}\r\n\r\n",
            st
        );
        let _ = sock.send_to(msg.as_bytes(), "239.255.255.250:1900");
    }

    let deadline = Instant::now() + timeout;
    let mut buf = [0u8; 4096];
    while Instant::now() < deadline {
        match sock.recv_from(&mut buf) {
            Ok((n, _)) => {
                let text = String::from_utf8_lossy(&buf[..n]);
                for line in text.lines() {
                    if let Some((k, v)) = line.split_once(':') {
                        if k.trim().eq_ignore_ascii_case("LOCATION") {
                            let loc = v.trim();
                            if !loc.is_empty() {
                                locations.insert(loc.to_string());
                            }
                        }
                    }
                }
            }
            Err(_) => continue,
        }
    }
    locations.into_iter().collect()
}

fn get_local_ip_for_target(host: &str) -> String {
    if let Ok(sock) = UdpSocket::bind("0.0.0.0:0") {
        if sock.connect(format!("{}:1900", host)).is_ok() {
            if let Ok(addr) = sock.local_addr() {
                if let IpAddr::V4(ip) = addr.ip() {
                    if ip != Ipv4Addr::UNSPECIFIED {
                        return ip.to_string();
                    }
                }
            }
        }
    }
    "127.0.0.1".to_string()
}

fn extract_tag(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{}", tag);
    let close = format!("</{}>", tag);
    let start_pos = xml.find(&open)?;
    let after_open = xml[start_pos..].find('>')? + start_pos + 1;
    let end_pos = xml[after_open..].find(&close)? + after_open;
    Some(xml[after_open..end_pos].trim().to_string())
}

pub fn parse_dlna_device_xml(xml: &str, location: &str) -> Option<DlnaDevice> {
    let name = extract_tag(xml, "friendlyName").unwrap_or_else(|| "DLNA 电视设备".to_string());
    let base_str = extract_tag(xml, "URLBase").unwrap_or_else(|| location.to_string());

    let mut search_from = 0;
    let mut control_path = None;
    while let Some(s_idx) = xml[search_from..].find("<service>") {
        let abs_start = search_from + s_idx;
        let Some(e_idx) = xml[abs_start..].find("</service>") else {
            break;
        };
        let block = &xml[abs_start..abs_start + e_idx];
        if block.contains("AVTransport") {
            if let Some(ctrl) = extract_tag(block, "controlURL") {
                control_path = Some(ctrl);
                break;
            }
        }
        search_from = abs_start + e_idx + 10;
    }

    let ctrl = control_path?;
    let base = reqwest::Url::parse(&base_str).ok()?;
    let full_ctrl = base.join(&ctrl).ok()?;
    let host = full_ctrl.host_str()?.to_string();

    Some(DlnaDevice {
        id: location.to_string(),
        name,
        host,
        location: location.to_string(),
        control_url: full_ctrl.to_string(),
    })
}

fn xml_escape(input: &str) -> String {
    input
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}
