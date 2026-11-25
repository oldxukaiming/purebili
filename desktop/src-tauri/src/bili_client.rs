use anyhow::{anyhow, Result};
use base64::Engine;
use md5::{Digest, Md5};
use reqwest::header::{HeaderMap, HeaderValue, COOKIE, REFERER, SET_COOKIE, USER_AGENT};
use rsa::{pkcs8::DecodePublicKey, Pkcs1v15Encrypt, RsaPublicKey};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::RwLock;

pub const DEFAULT_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
pub const DEFAULT_REFERER: &str = "https://www.bilibili.com/";

const MIXIN_KEY_ENC_TAB: [usize; 64] = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19,
    29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
];

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SessionData {
    pub cookies: HashMap<String, String>,
    pub uid: Option<u64>,
    pub uname: Option<String>,
    pub face: Option<String>,
    pub wbi_img_key: Option<String>,
    pub wbi_sub_key: Option<String>,
    pub wbi_updated_at: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserProfile {
    pub is_logged_in: bool,
    pub uid: u64,
    pub uname: String,
    pub face: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QrCodeInfo {
    pub url: String,
    pub qrcode_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QrPollResult {
    pub status: i64,
    pub message: String,
    pub logged_in: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CaptchaInfo {
    pub token: String,
    pub gt: String,
    pub challenge: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PasswordLoginResult {
    pub success: bool,
    pub message: String,
    pub verify_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FollowingUp {
    pub mid: u64,
    pub uname: String,
    pub face: String,
    pub sign: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FollowingPage {
    pub list: Vec<FollowingUp>,
    pub total: u64,
    pub has_more: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FollowingTag {
    pub tagid: i64,
    pub name: String,
    pub count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VideoItem {
    pub bvid: String,
    pub aid: u64,
    pub title: String,
    pub cover: String,
    pub duration_text: String,
    pub author_name: String,
    pub author_mid: u64,
    pub author_face: String,
    pub pub_text: String,
    pub play_count: String,
    pub invalid: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedPage {
    pub items: Vec<VideoItem>,
    pub next_offset: String,
    pub has_more: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FavFolder {
    pub id: u64,
    pub title: String,
    pub media_count: u64,
    pub is_collected: bool,
    pub fav_state: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VideoPagePart {
    pub cid: u64,
    pub page: u64,
    pub part: String,
    pub duration: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VideoDetail {
    pub bvid: String,
    pub aid: u64,
    pub title: String,
    pub desc: String,
    pub cover: String,
    pub author_name: String,
    pub author_mid: u64,
    pub author_face: String,
    pub pages: Vec<VideoPagePart>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityOption {
    pub qn: u64,
    pub desc: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlayInfo {
    pub bvid: String,
    pub cid: u64,
    pub current_qn: u64,
    pub qualities: Vec<QualityOption>,
    pub dash_mpd_url: Option<String>,
    pub mp4_url: Option<String>,
}

#[derive(Debug, Clone)]
pub struct DashStreamMeta {
    pub id: u64,
    pub base_url: String,
    pub bandwidth: u64,
    pub mime_type: String,
    pub codecs: String,
    pub width: u64,
    pub height: u64,
    pub frame_rate: String,
    pub init_range: String,
    pub index_range: String,
}

#[derive(Debug, Clone)]
pub struct CachedDash {
    pub duration: u64,
    pub videos: Vec<DashStreamMeta>,
    pub audios: Vec<DashStreamMeta>,
}

pub struct BiliClient {
    pub http: reqwest::Client,
    pub session: Arc<RwLock<SessionData>>,
    pub session_path: PathBuf,
    pub dash_cache: Arc<RwLock<HashMap<String, CachedDash>>>,
    pub allowed_hosts: Arc<RwLock<std::collections::HashSet<String>>>,
    pub proxy_port: Arc<RwLock<u16>>,
}

impl BiliClient {
    pub fn new(session_path: PathBuf) -> Result<Self> {
        let http = reqwest::Client::builder()
            .user_agent(DEFAULT_UA)
            .timeout(std::time::Duration::from_secs(20))
            .build()?;

        let session_data = if session_path.exists() {
            let text = std::fs::read_to_string(&session_path).unwrap_or_default();
            serde_json::from_str::<SessionData>(&text).unwrap_or_default()
        } else {
            SessionData::default()
        };

        Ok(Self {
            http,
            session: Arc::new(RwLock::new(session_data)),
            session_path,
            dash_cache: Arc::new(RwLock::new(HashMap::new())),
            allowed_hosts: Arc::new(RwLock::new(std::collections::HashSet::new())),
            proxy_port: Arc::new(RwLock::new(0)),
        })
    }

    pub async fn register_allowed_url(&self, raw: &str) {
        if let Ok(parsed) = reqwest::Url::parse(raw) {
            if let Some(host) = parsed.host_str() {
                let h = host.to_ascii_lowercase();
                if h != "localhost"
                    && !h.starts_with("127.")
                    && !h.starts_with("10.")
                    && !h.starts_with("192.168.")
                    && !h.starts_with("169.254.")
                {
                    self.allowed_hosts.write().await.insert(h);
                }
            }
        }
    }

    pub async fn set_proxy_port(&self, port: u16) {
        *self.proxy_port.write().await = port;
    }

    pub async fn get_proxy_port(&self) -> u16 {
        *self.proxy_port.read().await
    }

    pub fn proxy_image_url(&self, port: u16, raw: &str) -> String {
        if raw.is_empty() || port == 0 {
            return raw.to_string();
        }
        let normalized = if raw.starts_with("//") {
            format!("https:{}", raw)
        } else if raw.starts_with("http://") {
            raw.replacen("http://", "https://", 1)
        } else {
            raw.to_string()
        };
        format!(
            "http://127.0.0.1:{}/proxy?url={}",
            port,
            urlencoding::encode(&normalized)
        )
    }

    async fn save_session(&self) {
        let data = self.session.read().await.clone();
        if let Some(parent) = self.session_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(json) = serde_json::to_string_pretty(&data) {
            let _ = std::fs::write(&self.session_path, json);
        }
    }

    pub async fn cookie_header(&self) -> String {
        let guard = self.session.read().await;
        guard
            .cookies
            .iter()
            .map(|(k, v)| format!("{}={}", k, v))
            .collect::<Vec<_>>()
            .join("; ")
    }

    async fn store_set_cookies(&self, headers: &HeaderMap) {
        let mut changed = false;
        {
            let mut guard = self.session.write().await;
            for val in headers.get_all(SET_COOKIE) {
                if let Ok(s) = val.to_str() {
                    if let Some(kv) = s.split(';').next() {
                        if let Some((k, v)) = kv.split_once('=') {
                            let key = k.trim().to_string();
                            let value = v.trim().to_string();
                            if !key.is_empty() && !value.is_empty() {
                                guard.cookies.insert(key, value);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        if changed {
            self.save_session().await;
        }
    }

    async fn build_headers(&self) -> HeaderMap {
        let mut headers = HeaderMap::new();
        headers.insert(USER_AGENT, HeaderValue::from_static(DEFAULT_UA));
        headers.insert(REFERER, HeaderValue::from_static(DEFAULT_REFERER));
        headers.insert(
            reqwest::header::ORIGIN,
            HeaderValue::from_static("https://www.bilibili.com"),
        );
        headers.insert(
            reqwest::header::ACCEPT,
            HeaderValue::from_static("application/json, text/plain, */*"),
        );
        headers.insert(
            reqwest::header::ACCEPT_LANGUAGE,
            HeaderValue::from_static("zh-CN,zh;q=0.9"),
        );
        let cookie_str = self.cookie_header().await;
        if !cookie_str.is_empty() {
            if let Ok(val) = HeaderValue::from_str(&cookie_str) {
                headers.insert(COOKIE, val);
            }
        }
        headers
    }

    pub async fn get_json(&self, url: &str) -> Result<Value> {
        let headers = self.build_headers().await;
        let resp = self.http.get(url).headers(headers).send().await?;
        let status = resp.status();
        self.store_set_cookies(resp.headers()).await;
        let text = resp.text().await?;
        serde_json::from_str::<Value>(&text)
            .map_err(|_| anyhow!("B站接口返回异常响应 (HTTP {})", status))
    }

    pub async fn ensure_buvid(&self) -> Result<()> {
        let has_buvid = {
            let guard = self.session.read().await;
            guard.cookies.contains_key("buvid3")
                && guard.cookies.contains_key("buvid4")
                && guard.cookies.contains_key("b_nut")
        };
        if has_buvid {
            return Ok(());
        }
        if let Ok(home_resp) = self
            .http
            .get("https://www.bilibili.com/")
            .header(USER_AGENT, DEFAULT_UA)
            .header(reqwest::header::ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9")
            .send()
            .await
        {
            self.store_set_cookies(home_resp.headers()).await;
        }
        let val = self
            .get_json("https://api.bilibili.com/x/frontend/finger/spi")
            .await?;
        if let Some(data) = val.get("data") {
            let mut guard = self.session.write().await;
            if let Some(b3) = data.get("b_3").and_then(|v| v.as_str()) {
                guard.cookies.insert("buvid3".to_string(), b3.to_string());
            }
            if let Some(b4) = data.get("b_4").and_then(|v| v.as_str()) {
                guard.cookies.insert("buvid4".to_string(), b4.to_string());
            }
        }
        self.save_session().await;
        Ok(())
    }

    pub async fn logout(&self) -> Result<()> {
        {
            let mut guard = self.session.write().await;
            guard.cookies.clear();
            guard.uid = None;
            guard.uname = None;
            guard.face = None;
        }
        self.save_session().await;
        let _ = self.ensure_buvid().await;
        Ok(())
    }

    pub async fn get_profile(&self) -> Result<UserProfile> {
        let _ = self.ensure_buvid().await;
        let val = self
            .get_json("https://api.bilibili.com/x/web-interface/nav")
            .await?;
        let data = &val["data"];
        let is_login = data["isLogin"].as_bool().unwrap_or(false);

        if let Some(wbi_img) = data.get("wbi_img") {
            let img_url = wbi_img["img_url"].as_str().unwrap_or("");
            let sub_url = wbi_img["sub_url"].as_str().unwrap_or("");
            let img_key = extract_wbi_key(img_url);
            let sub_key = extract_wbi_key(sub_url);
            if !img_key.is_empty() && !sub_key.is_empty() {
                let mut guard = self.session.write().await;
                guard.wbi_img_key = Some(img_key);
                guard.wbi_sub_key = Some(sub_key);
                guard.wbi_updated_at = Some(now_secs());
            }
        }

        if is_login {
            let uid = data["mid"].as_u64().unwrap_or(0);
            let uname = data["uname"].as_str().unwrap_or("B站用户").to_string();
            let raw_face = data["face"].as_str().unwrap_or("").to_string();
            {
                let mut guard = self.session.write().await;
                guard.uid = Some(uid);
                guard.uname = Some(uname.clone());
                guard.face = Some(raw_face.clone());
            }
            self.save_session().await;
            let port = self.get_proxy_port().await;
            Ok(UserProfile {
                is_logged_in: true,
                uid,
                uname,
                face: self.proxy_image_url(port, &raw_face),
            })
        } else {
            self.save_session().await;
            Ok(UserProfile {
                is_logged_in: false,
                uid: 0,
                uname: String::new(),
                face: String::new(),
            })
        }
    }

    async fn get_mixin_key(&self) -> Result<String> {
        let now = now_secs();
        let cached = {
            let guard = self.session.read().await;
            match (
                &guard.wbi_img_key,
                &guard.wbi_sub_key,
                guard.wbi_updated_at,
            ) {
                (Some(img), Some(sub), Some(ts)) if now.saturating_sub(ts) < 3600 => {
                    Some((img.clone(), sub.clone()))
                }
                _ => None,
            }
        };

        let (img_key, sub_key) = if let Some(pair) = cached {
            pair
        } else {
            let _ = self.get_profile().await?;
            let guard = self.session.read().await;
            let img = guard
                .wbi_img_key
                .clone()
                .ok_or_else(|| anyhow!("Missing wbi img key"))?;
            let sub = guard
                .wbi_sub_key
                .clone()
                .ok_or_else(|| anyhow!("Missing wbi sub key"))?;
            (img, sub)
        };

        let raw = format!("{}{}", img_key, sub_key);
        let bytes = raw.as_bytes();
        let mut mixin = String::with_capacity(32);
        for &idx in MIXIN_KEY_ENC_TAB.iter().take(32) {
            if let Some(&b) = bytes.get(idx) {
                mixin.push(b as char);
            }
        }
        Ok(mixin)
    }

    pub async fn sign_wbi_query(&self, params: BTreeMap<&str, String>) -> Result<String> {
        let mixin_key = self.get_mixin_key().await?;
        let mut sorted: BTreeMap<String, String> = params
            .into_iter()
            .map(|(k, v)| (k.to_string(), v))
            .collect();
        sorted.insert("wts".to_string(), now_secs().to_string());

        let query = sorted
            .iter()
            .map(|(k, v)| {
                let filtered: String = v
                    .chars()
                    .filter(|c| !matches!(c, '!' | '\'' | '(' | ')' | '*'))
                    .collect();
                format!(
                    "{}={}",
                    urlencoding::encode(k),
                    urlencoding::encode(&filtered)
                )
            })
            .collect::<Vec<_>>()
            .join("&");

        let mut hasher = Md5::new();
        hasher.update(format!("{}{}", query, mixin_key).as_bytes());
        let w_rid = format!("{:x}", hasher.finalize());
        Ok(format!("{}&w_rid={}", query, w_rid))
    }

    pub async fn qrcode_generate(&self) -> Result<QrCodeInfo> {
        let _ = self.ensure_buvid().await;
        let val = self
            .get_json("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
            .await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "获取二维码失败"));
        }
        Ok(QrCodeInfo {
            url: val["data"]["url"].as_str().unwrap_or("").to_string(),
            qrcode_key: val["data"]["qrcode_key"]
                .as_str()
                .unwrap_or("")
                .to_string(),
        })
    }

    pub async fn qrcode_poll(&self, qrcode_key: &str) -> Result<QrPollResult> {
        let url = format!(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key={}",
            urlencoding::encode(qrcode_key)
        );
        let val = self.get_json(&url).await?;
        let code = val["data"]["code"].as_i64().unwrap_or(-1);
        let message = val["data"]["message"]
            .as_str()
            .unwrap_or("未知状态")
            .to_string();
        if code == 0 {
            let _ = self.get_profile().await;
            return Ok(QrPollResult {
                status: 0,
                message: "登录成功".to_string(),
                logged_in: true,
            });
        }
        Ok(QrPollResult {
            status: code,
            message,
            logged_in: false,
        })
    }

    pub async fn password_captcha(&self) -> Result<CaptchaInfo> {
        let _ = self.ensure_buvid().await;
        let val = self
            .get_json("https://passport.bilibili.com/x/passport-login/captcha?source=main_web")
            .await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "获取人机验证参数失败"));
        }
        let data = &val["data"];
        Ok(CaptchaInfo {
            token: data["token"].as_str().unwrap_or("").to_string(),
            gt: data["geetest"]["gt"].as_str().unwrap_or("").to_string(),
            challenge: data["geetest"]["challenge"]
                .as_str()
                .unwrap_or("")
                .to_string(),
        })
    }

    pub async fn password_login(
        &self,
        username: &str,
        password: &str,
        token: &str,
        challenge: &str,
        validate: &str,
        seccode: &str,
    ) -> Result<PasswordLoginResult> {
        let key_val = self
            .get_json("https://passport.bilibili.com/x/passport-login/web/key")
            .await?;
        if key_val["code"].as_i64() != Some(0) {
            return Err(anyhow!("获取加密公钥失败"));
        }
        let hash = key_val["data"]["hash"]
            .as_str()
            .ok_or_else(|| anyhow!("Missing RSA hash"))?;
        let pem = key_val["data"]["key"]
            .as_str()
            .ok_or_else(|| anyhow!("Missing RSA public key"))?;

        let pub_key = RsaPublicKey::from_public_key_pem(pem)?;
        let raw_payload = format!("{}{}", hash, password);
        let encrypted = pub_key.encrypt(
            &mut rand::thread_rng(),
            Pkcs1v15Encrypt,
            raw_payload.as_bytes(),
        )?;
        let encoded_pwd = base64::engine::general_purpose::STANDARD.encode(encrypted);

        let headers = self.build_headers().await;
        let form = [
            ("username", username),
            ("password", encoded_pwd.as_str()),
            ("keep", "0"),
            ("token", token),
            ("challenge", challenge),
            ("validate", validate),
            ("seccode", seccode),
            ("source", "main_web"),
            ("go_url", "https://www.bilibili.com"),
        ];

        let resp = self
            .http
            .post("https://passport.bilibili.com/x/passport-login/web/login")
            .headers(headers)
            .form(&form)
            .send()
            .await?;
        self.store_set_cookies(resp.headers()).await;
        let res_val: Value = resp.json().await?;
        let code = res_val["code"].as_i64().unwrap_or(-1);
        if code != 0 {
            return Ok(PasswordLoginResult {
                success: false,
                message: res_val["message"]
                    .as_str()
                    .unwrap_or("登录失败")
                    .to_string(),
                verify_url: None,
            });
        }
        let status = res_val["data"]["status"].as_i64().unwrap_or(0);
        if status == 0 {
            let _ = self.get_profile().await;
            return Ok(PasswordLoginResult {
                success: true,
                message: "登录成功".to_string(),
                verify_url: None,
            });
        }
        let verify_url = res_val["data"]["url"]
            .as_str()
            .map(|s| s.to_string())
            .filter(|s| !s.is_empty());
        let msg = res_val["data"]["message"]
            .as_str()
            .unwrap_or("账号触发安全验证，建议使用扫码登录")
            .to_string();
        Ok(PasswordLoginResult {
            success: false,
            message: msg,
            verify_url,
        })
    }

    pub async fn get_followings(&self, pn: u64, ps: u64) -> Result<FollowingPage> {
        let uid = self.get_uid_or_err().await?;
        let ps_clamped = ps.clamp(1, 50);
        let url = format!(
            "https://api.bilibili.com/x/relation/followings?vmid={}&pn={}&ps={}&order=desc",
            uid, pn, ps_clamped
        );
        let val = self.get_json(&url).await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "获取关注列表失败"));
        }
        let port = self.get_proxy_port().await;
        let list: Vec<FollowingUp> = val["data"]["list"]
            .as_array()
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .map(|u| FollowingUp {
                mid: u["mid"].as_u64().unwrap_or(0),
                uname: u["uname"].as_str().unwrap_or("").to_string(),
                face: self.proxy_image_url(port, u["face"].as_str().unwrap_or("")),
                sign: u["sign"].as_str().unwrap_or("").to_string(),
            })
            .collect();
        let total = val["data"]["total"].as_u64().unwrap_or(list.len() as u64);
        let has_more =
            !list.is_empty() && ((pn.saturating_sub(1)) * ps_clamped + list.len() as u64) < total;
        Ok(FollowingPage {
            list,
            total,
            has_more,
        })
    }

    pub async fn search_followings(
        &self,
        name: &str,
        pn: u64,
        ps: u64,
    ) -> Result<FollowingPage> {
        let uid = self.get_uid_or_err().await?;
        let ps_clamped = ps.clamp(1, 50);
        let url = format!(
            "https://api.bilibili.com/x/relation/followings/search?vmid={}&name={}&pn={}&ps={}",
            uid,
            urlencoding::encode(name),
            pn,
            ps_clamped
        );
        let val = self.get_json(&url).await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "搜索关注 UP 主失败"));
        }
        let port = self.get_proxy_port().await;
        let list: Vec<FollowingUp> = val["data"]["list"]
            .as_array()
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .map(|u| FollowingUp {
                mid: u["mid"].as_u64().unwrap_or(0),
                uname: u["uname"].as_str().unwrap_or("").to_string(),
                face: self.proxy_image_url(port, u["face"].as_str().unwrap_or("")),
                sign: u["sign"].as_str().unwrap_or("").to_string(),
            })
            .collect();
        let total = val["data"]["total"].as_u64().unwrap_or(list.len() as u64);
        let has_more =
            !list.is_empty() && ((pn.saturating_sub(1)) * ps_clamped + list.len() as u64) < total;
        Ok(FollowingPage {
            list,
            total,
            has_more,
        })
    }

    pub async fn get_following_tags(&self) -> Result<Vec<FollowingTag>> {
        let val = self
            .get_json("https://api.bilibili.com/x/relation/tags")
            .await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "获取关注分组失败"));
        }
        let tags = val["data"]
            .as_array()
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .map(|t| FollowingTag {
                tagid: t["tagid"].as_i64().unwrap_or(0),
                name: t["name"].as_str().unwrap_or("分组").to_string(),
                count: t["count"].as_u64().unwrap_or(0),
            })
            .collect();
        Ok(tags)
    }

    pub async fn get_tag_followings(
        &self,
        tagid: i64,
        pn: u64,
        ps: u64,
    ) -> Result<FollowingPage> {
        let uid = self.get_uid_or_err().await?;
        let ps_clamped = ps.clamp(1, 50);
        let url = format!(
            "https://api.bilibili.com/x/relation/tag?mid={}&tagid={}&pn={}&ps={}",
            uid, tagid, pn, ps_clamped
        );
        let val = self.get_json(&url).await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "获取分组关注列表失败"));
        }
        let port = self.get_proxy_port().await;
        let list: Vec<FollowingUp> = val["data"]
            .as_array()
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .map(|u| FollowingUp {
                mid: u["mid"].as_u64().unwrap_or(0),
                uname: u["uname"].as_str().unwrap_or("").to_string(),
                face: self.proxy_image_url(port, u["face"].as_str().unwrap_or("")),
                sign: u["sign"].as_str().unwrap_or("").to_string(),
            })
            .collect();
        let has_more = list.len() as u64 >= ps_clamped;
        let total = list.len() as u64;
        Ok(FollowingPage {
            list,
            total,
            has_more,
        })
    }

    pub async fn unfollow_up(&self, mid: u64) -> Result<()> {
        let csrf = {
            let guard = self.session.read().await;
            guard.cookies.get("bili_jct").cloned().unwrap_or_default()
        };
        if csrf.is_empty() {
            return Err(anyhow!("登录凭据缺少 CSRF (bili_jct)，请重新登录"));
        }
        let headers = self.build_headers().await;
        let fid_str = mid.to_string();
        let form = [
            ("fid", fid_str.as_str()),
            ("act", "2"),
            ("re_src", "11"),
            ("csrf", csrf.as_str()),
        ];
        let resp = self
            .http
            .post("https://api.bilibili.com/x/relation/modify")
            .headers(headers)
            .form(&form)
            .send()
            .await?;
        let status = resp.status();
        self.store_set_cookies(resp.headers()).await;
        let text = resp.text().await?;
        let val: Value = serde_json::from_str(&text)
            .map_err(|_| anyhow!("取消关注失败 (HTTP {})", status))?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "取消关注失败"));
        }
        Ok(())
    }

    pub async fn get_raw_mp4_url(&self, bvid: &str, cid: u64, qn: u64) -> Result<String> {
        let mut mp4_params = BTreeMap::new();
        mp4_params.insert("bvid", bvid.to_string());
        mp4_params.insert("cid", cid.to_string());
        mp4_params.insert("qn", qn.to_string());
        mp4_params.insert("fnval", "1".to_string());
        mp4_params.insert("fnver", "0".to_string());
        let mp4_query = self.sign_wbi_query(mp4_params).await?;
        let mp4_api = format!("https://api.bilibili.com/x/player/wbi/playurl?{}", mp4_query);
        let mp4_val = self.get_json(&mp4_api).await?;
        if mp4_val["code"].as_i64() != Some(0) {
            return Err(api_err(&mp4_val, "获取投屏流地址失败"));
        }
        let durl0 = mp4_val["data"]["durl"]
            .as_array()
            .and_then(|arr| arr.first())
            .ok_or_else(|| anyhow!("当前视频暂无可用的 MP4 投屏流"))?;
        let chosen = pick_best_url(durl0, "url");
        if chosen.is_empty() {
            return Err(anyhow!("未解析到投屏流 URL"));
        }
        Ok(chosen)
    }

    pub async fn get_following_feed(&self, offset: &str) -> Result<FeedPage> {
        let url = if offset.is_empty() {
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=video".to_string()
        } else {
            format!(
                "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=video&offset={}",
                urlencoding::encode(offset)
            )
        };
        let val = self.get_json(&url).await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "获取关注视频流失败"));
        }

        let port = self.get_proxy_port().await;
        let mut videos = Vec::new();
        if let Some(items) = val["data"]["items"].as_array() {
            for item in items {
                let author = &item["modules"]["module_author"];
                let author_name = author["name"].as_str().unwrap_or("").to_string();
                let author_mid = author["mid"].as_u64().unwrap_or(0);
                let author_face =
                    self.proxy_image_url(port, author["face"].as_str().unwrap_or(""));
                let pub_text = author["pub_time"].as_str().unwrap_or("").to_string();

                let major = &item["modules"]["module_dynamic"]["major"];
                let mtype = major["type"].as_str().unwrap_or("");
                if mtype == "MAJOR_TYPE_ARCHIVE" {
                    let arc = &major["archive"];
                    let bvid = arc["bvid"].as_str().unwrap_or("").to_string();
                    if bvid.is_empty() {
                        continue;
                    }
                    videos.push(VideoItem {
                        bvid,
                        aid: arc["aid"]
                            .as_str()
                            .and_then(|s| s.parse().ok())
                            .or_else(|| arc["aid"].as_u64())
                            .unwrap_or(0),
                        title: arc["title"].as_str().unwrap_or("").to_string(),
                        cover: self.proxy_image_url(port, arc["cover"].as_str().unwrap_or("")),
                        duration_text: arc["duration_text"].as_str().unwrap_or("").to_string(),
                        author_name,
                        author_mid,
                        author_face,
                        pub_text,
                        play_count: arc["stat"]["play"].as_str().unwrap_or("").to_string(),
                        invalid: false,
                    });
                }
            }
        }

        Ok(FeedPage {
            items: videos,
            next_offset: val["data"]["offset"].as_str().unwrap_or("").to_string(),
            has_more: val["data"]["has_more"].as_bool().unwrap_or(false),
        })
    }

    pub async fn get_up_videos(&self, mid: u64, pn: u64, ps: u64) -> Result<FeedPage> {
        let mut params = BTreeMap::new();
        params.insert("mid", mid.to_string());
        params.insert("pn", pn.to_string());
        params.insert("ps", ps.to_string());
        params.insert("order", "pubdate".to_string());
        let query = self.sign_wbi_query(params).await?;
        let url = format!("https://api.bilibili.com/x/space/wbi/arc/search?{}", query);
        let val = self.get_json(&url).await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "获取 UP 主视频失败"));
        }

        let port = self.get_proxy_port().await;
        let vlist = val["data"]["list"]["vlist"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        let count = val["data"]["page"]["count"].as_u64().unwrap_or(0);
        let items: Vec<VideoItem> = vlist
            .into_iter()
            .map(|v| VideoItem {
                bvid: v["bvid"].as_str().unwrap_or("").to_string(),
                aid: v["aid"].as_u64().unwrap_or(0),
                title: v["title"].as_str().unwrap_or("").to_string(),
                cover: self.proxy_image_url(port, v["pic"].as_str().unwrap_or("")),
                duration_text: v["length"].as_str().unwrap_or("").to_string(),
                author_name: v["author"].as_str().unwrap_or("").to_string(),
                author_mid: v["mid"].as_u64().unwrap_or(mid),
                author_face: String::new(),
                pub_text: format_timestamp(v["created"].as_u64().unwrap_or(0)),
                play_count: format_count(v["play"].as_u64().unwrap_or(0)),
                invalid: false,
            })
            .collect();

        Ok(FeedPage {
            items,
            next_offset: (pn + 1).to_string(),
            has_more: pn * ps < count,
        })
    }

    pub async fn get_fav_folders(&self) -> Result<Vec<FavFolder>> {
        let uid = self.get_uid_or_err().await?;
        let mut folders = Vec::new();

        let created_url = format!(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid={}",
            uid
        );
        if let Ok(val) = self.get_json(&created_url).await {
            if val["code"].as_i64() == Some(0) {
                if let Some(list) = val["data"]["list"].as_array() {
                    for f in list {
                        folders.push(FavFolder {
                            id: f["id"].as_u64().unwrap_or(0),
                            title: f["title"].as_str().unwrap_or("默认收藏夹").to_string(),
                            media_count: f["media_count"].as_u64().unwrap_or(0),
                            is_collected: false,
                            fav_state: f["fav_state"].as_i64().unwrap_or(0),
                        });
                    }
                }
            }
        }

        let collected_url = format!(
            "https://api.bilibili.com/x/v3/fav/folder/collected/list?up_mid={}&pn=1&ps=50",
            uid
        );
        if let Ok(val) = self.get_json(&collected_url).await {
            if val["code"].as_i64() == Some(0) {
                if let Some(list) = val["data"]["list"].as_array() {
                    for f in list {
                        folders.push(FavFolder {
                            id: f["id"].as_u64().unwrap_or(0),
                            title: format!(
                                "{} (订阅)",
                                f["title"].as_str().unwrap_or("订阅收藏夹")
                            ),
                            media_count: f["media_count"].as_u64().unwrap_or(0),
                            is_collected: true,
                            fav_state: 0,
                        });
                    }
                }
            }
        }

        Ok(folders)
    }

    pub async fn get_video_fav_folders(&self, aid: u64) -> Result<Vec<FavFolder>> {
        let uid = self.get_uid_or_err().await?;
        let url = format!(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid={}&rid={}&type=2",
            uid, aid
        );
        let val = self.get_json(&url).await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "查询收藏状态失败"));
        }
        let folders = val["data"]["list"]
            .as_array()
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .map(|f| FavFolder {
                id: f["id"].as_u64().unwrap_or(0),
                title: f["title"].as_str().unwrap_or("默认收藏夹").to_string(),
                media_count: f["media_count"].as_u64().unwrap_or(0),
                is_collected: false,
                fav_state: f["fav_state"].as_i64().unwrap_or(0),
            })
            .collect();
        Ok(folders)
    }

    pub async fn unfavorite_video(&self, aid: u64, media_ids: Vec<u64>) -> Result<()> {
        let csrf = {
            let guard = self.session.read().await;
            guard.cookies.get("bili_jct").cloned().unwrap_or_default()
        };
        if csrf.is_empty() {
            return Err(anyhow!("登录凭据缺少 CSRF (bili_jct)，请重新登录"));
        }
        if aid == 0 || media_ids.is_empty() {
            return Err(anyhow!("缺少视频 aid 或收藏夹 ID"));
        }
        let del_ids = media_ids
            .iter()
            .map(|id| id.to_string())
            .collect::<Vec<_>>()
            .join(",");
        let rid_str = aid.to_string();
        let headers = self.build_headers().await;
        let form = [
            ("rid", rid_str.as_str()),
            ("type", "2"),
            ("add_media_ids", ""),
            ("del_media_ids", del_ids.as_str()),
            ("csrf", csrf.as_str()),
        ];
        let resp = self
            .http
            .post("https://api.bilibili.com/x/v3/fav/resource/deal")
            .headers(headers)
            .form(&form)
            .send()
            .await?;
        let status = resp.status();
        self.store_set_cookies(resp.headers()).await;
        let text = resp.text().await?;
        let val: Value = serde_json::from_str(&text)
            .map_err(|_| anyhow!("取消收藏失败 (HTTP {})", status))?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "取消收藏失败"));
        }
        Ok(())
    }

    pub async fn get_fav_videos(
        &self,
        media_id: u64,
        pn: u64,
        ps: u64,
        keyword: &str,
    ) -> Result<FeedPage> {
        let url = format!(
            "https://api.bilibili.com/x/v3/fav/resource/list?media_id={}&pn={}&ps={}&keyword={}&platform=web",
            media_id,
            pn,
            ps,
            urlencoding::encode(keyword)
        );
        let val = self.get_json(&url).await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "获取收藏夹内容失败"));
        }
        let port = self.get_proxy_port().await;
        let medias = val["data"]["medias"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        let items: Vec<VideoItem> = medias
            .into_iter()
            .filter(|m| m["type"].as_i64().unwrap_or(2) == 2)
            .map(|m| {
                let attr = m["attr"].as_i64().unwrap_or(0);
                let title = m["title"].as_str().unwrap_or("").to_string();
                let invalid = attr == 9 || title == "已失效视频";
                VideoItem {
                    bvid: m["bvid"].as_str().unwrap_or("").to_string(),
                    aid: m["id"].as_u64().unwrap_or(0),
                    title,
                    cover: self.proxy_image_url(port, m["cover"].as_str().unwrap_or("")),
                    duration_text: format_duration(m["duration"].as_u64().unwrap_or(0)),
                    author_name: m["upper"]["name"].as_str().unwrap_or("").to_string(),
                    author_mid: m["upper"]["mid"].as_u64().unwrap_or(0),
                    author_face: self
                        .proxy_image_url(port, m["upper"]["face"].as_str().unwrap_or("")),
                    pub_text: format_timestamp(m["fav_time"].as_u64().unwrap_or(0)),
                    play_count: format_count(m["cnt_info"]["play"].as_u64().unwrap_or(0)),
                    invalid,
                }
            })
            .collect();

        Ok(FeedPage {
            items,
            next_offset: (pn + 1).to_string(),
            has_more: val["data"]["has_more"].as_bool().unwrap_or(false),
        })
    }

    pub async fn get_video_detail(&self, bvid: &str) -> Result<VideoDetail> {
        let url = format!(
            "https://api.bilibili.com/x/web-interface/view?bvid={}",
            urlencoding::encode(bvid)
        );
        let val = self.get_json(&url).await?;
        if val["code"].as_i64() != Some(0) {
            return Err(api_err(&val, "获取视频详情失败"));
        }
        let d = &val["data"];
        let port = self.get_proxy_port().await;
        let pages = d["pages"]
            .as_array()
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .map(|p| VideoPagePart {
                cid: p["cid"].as_u64().unwrap_or(0),
                page: p["page"].as_u64().unwrap_or(1),
                part: p["part"].as_str().unwrap_or("正片").to_string(),
                duration: p["duration"].as_u64().unwrap_or(0),
            })
            .collect();

        Ok(VideoDetail {
            bvid: d["bvid"].as_str().unwrap_or(bvid).to_string(),
            aid: d["aid"].as_u64().unwrap_or(0),
            title: d["title"].as_str().unwrap_or("").to_string(),
            desc: d["desc"].as_str().unwrap_or("").to_string(),
            cover: self.proxy_image_url(port, d["pic"].as_str().unwrap_or("")),
            author_name: d["owner"]["name"].as_str().unwrap_or("").to_string(),
            author_mid: d["owner"]["mid"].as_u64().unwrap_or(0),
            author_face: self.proxy_image_url(port, d["owner"]["face"].as_str().unwrap_or("")),
            pages,
        })
    }

    pub async fn get_play_info(&self, bvid: &str, cid: u64, qn: u64) -> Result<PlayInfo> {
        let port = self.get_proxy_port().await;

        let mut dash_params = BTreeMap::new();
        dash_params.insert("bvid", bvid.to_string());
        dash_params.insert("cid", cid.to_string());
        dash_params.insert("qn", qn.to_string());
        dash_params.insert("fnval", "4048".to_string());
        dash_params.insert("fnver", "0".to_string());
        dash_params.insert("fourk", "1".to_string());
        let dash_query = self.sign_wbi_query(dash_params).await?;
        let dash_url = format!(
            "https://api.bilibili.com/x/player/wbi/playurl?{}",
            dash_query
        );
        let dash_val = self.get_json(&dash_url).await?;
        if dash_val["code"].as_i64() != Some(0) {
            return Err(api_err(&dash_val, "获取播放地址失败"));
        }

        let data = &dash_val["data"];
        let current_qn = data["quality"].as_u64().unwrap_or(qn);

        let accept_q = data["accept_quality"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        let accept_d = data["accept_description"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        let mut qualities = Vec::new();
        for (i, q) in accept_q.iter().enumerate() {
            if let Some(qv) = q.as_u64() {
                let desc = accept_d
                    .get(i)
                    .and_then(|v| v.as_str())
                    .unwrap_or("默认")
                    .to_string();
                qualities.push(QualityOption { qn: qv, desc });
            }
        }

        let mut dash_mpd_url = None;
        if let Some(dash) = data.get("dash") {
            let duration = dash["duration"].as_u64().unwrap_or(0);
            let videos = parse_dash_streams(dash.get("video"));
            let audios = parse_dash_streams(dash.get("audio"));
            for v in &videos {
                self.register_allowed_url(&v.base_url).await;
            }
            for a in &audios {
                self.register_allowed_url(&a.base_url).await;
            }
            if !videos.is_empty() && !audios.is_empty() {
                let cache_key = format!("{}_{}_{}", bvid, cid, qn);
                self.dash_cache.write().await.insert(
                    cache_key,
                    CachedDash {
                        duration,
                        videos,
                        audios,
                    },
                );
                dash_mpd_url = Some(format!(
                    "http://127.0.0.1:{}/dash.mpd?bvid={}&cid={}&qn={}",
                    port,
                    urlencoding::encode(bvid),
                    cid,
                    qn
                ));
            }
        }

        // Also fetch MP4 fallback for maximum compatibility
        let mut mp4_params = BTreeMap::new();
        mp4_params.insert("bvid", bvid.to_string());
        mp4_params.insert("cid", cid.to_string());
        mp4_params.insert("qn", qn.to_string());
        mp4_params.insert("fnval", "1".to_string());
        mp4_params.insert("fnver", "0".to_string());
        let mp4_query = self.sign_wbi_query(mp4_params).await?;
        let mp4_api = format!(
            "https://api.bilibili.com/x/player/wbi/playurl?{}",
            mp4_query
        );
        let mut mp4_url = None;
        if let Ok(mp4_val) = self.get_json(&mp4_api).await {
            if let Some(durl0) = mp4_val["data"]["durl"].as_array().and_then(|arr| arr.first()) {
                let chosen = pick_best_url(durl0, "url");
                if !chosen.is_empty() {
                    self.register_allowed_url(&chosen).await;
                    mp4_url = Some(format!(
                        "http://127.0.0.1:{}/proxy?url={}",
                        port,
                        urlencoding::encode(&chosen)
                    ));
                }
            }
        }

        Ok(PlayInfo {
            bvid: bvid.to_string(),
            cid,
            current_qn,
            qualities,
            dash_mpd_url,
            mp4_url,
        })
    }

    async fn get_uid_or_err(&self) -> Result<u64> {
        if let Some(uid) = self.session.read().await.uid {
            if uid > 0 {
                return Ok(uid);
            }
        }
        let profile = self.get_profile().await?;
        if profile.is_logged_in && profile.uid > 0 {
            Ok(profile.uid)
        } else {
            Err(anyhow!("未登录，请先扫码或输入密码登录"))
        }
    }
}

fn pick_best_url(obj: &Value, primary_field: &str) -> String {
    let mut candidates = Vec::new();
    if let Some(s) = obj[primary_field].as_str() {
        candidates.push(s.to_string());
    }
    if primary_field == "baseUrl" {
        if let Some(s) = obj["base_url"].as_str() {
            candidates.push(s.to_string());
        }
    }
    for key in ["backupUrl", "backup_url"] {
        if let Some(arr) = obj[key].as_array() {
            for item in arr {
                if let Some(s) = item.as_str() {
                    candidates.push(s.to_string());
                }
            }
        }
    }
    candidates
        .iter()
        .find(|u| u.contains("bilivideo.com") || u.contains("bilivideo.cn"))
        .cloned()
        .or_else(|| candidates.into_iter().next())
        .unwrap_or_default()
}

fn parse_dash_streams(arr_opt: Option<&Value>) -> Vec<DashStreamMeta> {
    arr_opt
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|s| {
            let base_url = pick_best_url(&s, "baseUrl");
            if base_url.is_empty() {
                return None;
            }
            let init_range = s["SegmentBase"]["Initialization"]
                .as_str()
                .or_else(|| s["segment_base"]["initialization"].as_str())
                .unwrap_or("0-999")
                .to_string();
            let index_range = s["SegmentBase"]["indexRange"]
                .as_str()
                .or_else(|| s["segment_base"]["index_range"].as_str())
                .unwrap_or("1000-2000")
                .to_string();
            Some(DashStreamMeta {
                id: s["id"].as_u64().unwrap_or(0),
                base_url,
                bandwidth: s["bandwidth"].as_u64().unwrap_or(0),
                mime_type: s["mimeType"]
                    .as_str()
                    .or_else(|| s["mime_type"].as_str())
                    .unwrap_or("video/mp4")
                    .to_string(),
                codecs: s["codecs"].as_str().unwrap_or("").to_string(),
                width: s["width"].as_u64().unwrap_or(0),
                height: s["height"].as_u64().unwrap_or(0),
                frame_rate: s["frameRate"]
                    .as_str()
                    .or_else(|| s["frame_rate"].as_str())
                    .unwrap_or("30")
                    .to_string(),
                init_range,
                index_range,
            })
        })
        .collect()
}

fn api_err(val: &Value, fallback: &str) -> anyhow::Error {
    anyhow!("{}", val["message"].as_str().unwrap_or(fallback))
}

fn extract_wbi_key(url: &str) -> String {
    url.rsplit('/')
        .next()
        .unwrap_or("")
        .split('.')
        .next()
        .unwrap_or("")
        .to_string()
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

pub fn format_duration(secs: u64) -> String {
    let h = secs / 3600;
    let m = (secs % 3600) / 60;
    let s = secs % 60;
    if h > 0 {
        format!("{}:{:02}:{:02}", h, m, s)
    } else {
        format!("{:02}:{:02}", m, s)
    }
}

fn format_count(n: u64) -> String {
    if n >= 10_000 {
        format!("{:.1}万", n as f64 / 10_000.0)
    } else {
        n.to_string()
    }
}

fn format_timestamp(ts: u64) -> String {
    if ts == 0 {
        return String::new();
    }
    let now = now_secs();
    let diff = now.saturating_sub(ts);
    if diff < 3600 {
        format!("{}分钟前", (diff / 60).max(1))
    } else if diff < 86400 {
        format!("{}小时前", diff / 3600)
    } else if diff < 86400 * 30 {
        format!("{}天前", diff / 86400)
    } else {
        let days_since_epoch = ts / 86400;
        let approx_year = 1970 + days_since_epoch / 365;
        format!("{}年", approx_year)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_format_duration() {
        assert_eq!(format_duration(65), "01:05");
        assert_eq!(format_duration(3665), "1:01:05");
    }

    #[test]
    fn test_extract_wbi_key() {
        assert_eq!(
            extract_wbi_key("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"),
            "7cd084941338484aae1ad9425b84077c"
        );
    }
}
