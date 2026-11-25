pub mod bili_client;
pub mod dlna;
pub mod proxy;

use bili_client::{
    BiliClient, CaptchaInfo, FavFolder, FeedPage, FollowingPage, FollowingTag, PasswordLoginResult,
    PlayInfo, QrCodeInfo, QrPollResult, UserProfile, VideoDetail,
};
use dlna::{CastResult, DlnaDevice, DlnaManager};
use std::sync::Arc;
use tauri::{AppHandle, Manager, State, WebviewUrl, WebviewWindowBuilder};

pub struct AppState {
    pub client: Arc<BiliClient>,
    pub dlna: Arc<DlnaManager>,
}

#[tauri::command]
async fn get_profile(state: State<'_, AppState>) -> Result<UserProfile, String> {
    state
        .client
        .get_profile()
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn logout(state: State<'_, AppState>) -> Result<(), String> {
    state.client.logout().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn qrcode_generate(state: State<'_, AppState>) -> Result<QrCodeInfo, String> {
    state
        .client
        .qrcode_generate()
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn qrcode_poll(state: State<'_, AppState>, qrcode_key: String) -> Result<QrPollResult, String> {
    state
        .client
        .qrcode_poll(&qrcode_key)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_password_captcha(state: State<'_, AppState>) -> Result<CaptchaInfo, String> {
    state
        .client
        .password_captcha()
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn open_geetest_window(
    app: AppHandle,
    state: State<'_, AppState>,
    token: String,
    gt: String,
    challenge: String,
) -> Result<(), String> {
    if let Some(existing) = app.get_webview_window("geetest") {
        let _ = existing.close();
    }
    let port = state.client.get_proxy_port().await;
    let url_str = format!(
        "http://127.0.0.1:{}/geetest?token={}&gt={}&challenge={}",
        port,
        urlencoding::encode(&token),
        urlencoding::encode(&gt),
        urlencoding::encode(&challenge)
    );
    let parsed: tauri::Url = url_str.parse().map_err(|e: url::ParseError| e.to_string())?;
    WebviewWindowBuilder::new(&app, "geetest", WebviewUrl::External(parsed))
        .title("安全验证 - PureBili")
        .inner_size(420.0, 460.0)
        .resizable(false)
        .center()
        .build()
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
async fn login_with_password(
    state: State<'_, AppState>,
    username: String,
    password: String,
    token: String,
    challenge: String,
    validate: String,
    seccode: String,
) -> Result<PasswordLoginResult, String> {
    state
        .client
        .password_login(&username, &password, &token, &challenge, &validate, &seccode)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_followings(
    state: State<'_, AppState>,
    pn: u64,
    ps: u64,
) -> Result<FollowingPage, String> {
    state
        .client
        .get_followings(pn, ps)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn search_followings(
    state: State<'_, AppState>,
    name: String,
    pn: u64,
    ps: u64,
) -> Result<FollowingPage, String> {
    state
        .client
        .search_followings(&name, pn, ps)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_following_tags(state: State<'_, AppState>) -> Result<Vec<FollowingTag>, String> {
    state
        .client
        .get_following_tags()
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_tag_followings(
    state: State<'_, AppState>,
    tagid: i64,
    pn: u64,
    ps: u64,
) -> Result<FollowingPage, String> {
    state
        .client
        .get_tag_followings(tagid, pn, ps)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_following_feed(
    state: State<'_, AppState>,
    offset: String,
) -> Result<FeedPage, String> {
    state
        .client
        .get_following_feed(&offset)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_up_videos(
    state: State<'_, AppState>,
    mid: u64,
    pn: u64,
    ps: u64,
) -> Result<FeedPage, String> {
    state
        .client
        .get_up_videos(mid, pn, ps)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_fav_folders(state: State<'_, AppState>) -> Result<Vec<FavFolder>, String> {
    state
        .client
        .get_fav_folders()
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_video_fav_folders(
    state: State<'_, AppState>,
    aid: u64,
) -> Result<Vec<FavFolder>, String> {
    state
        .client
        .get_video_fav_folders(aid)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn unfavorite_video(
    state: State<'_, AppState>,
    aid: u64,
    media_ids: Vec<u64>,
) -> Result<(), String> {
    state
        .client
        .unfavorite_video(aid, media_ids)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_fav_videos(
    state: State<'_, AppState>,
    media_id: u64,
    pn: u64,
    ps: u64,
    keyword: String,
) -> Result<FeedPage, String> {
    state
        .client
        .get_fav_videos(media_id, pn, ps, &keyword)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_video_detail(state: State<'_, AppState>, bvid: String) -> Result<VideoDetail, String> {
    state
        .client
        .get_video_detail(&bvid)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn unfollow_up(state: State<'_, AppState>, mid: u64) -> Result<(), String> {
    state
        .client
        .unfollow_up(mid)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn discover_dlna_devices(state: State<'_, AppState>) -> Result<Vec<DlnaDevice>, String> {
    state
        .dlna
        .discover_devices()
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn cast_video_to_dlna(
    state: State<'_, AppState>,
    control_url: String,
    bvid: String,
    cid: u64,
    qn: u64,
    title: String,
) -> Result<CastResult, String> {
    state
        .dlna
        .cast_video(&control_url, &bvid, cid, qn, &title)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn control_dlna(
    state: State<'_, AppState>,
    control_url: String,
    command: String,
) -> Result<(), String> {
    state
        .dlna
        .control_device(&control_url, &command)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_play_info(
    state: State<'_, AppState>,
    bvid: String,
    cid: u64,
    qn: u64,
) -> Result<PlayInfo, String> {
    state
        .client
        .get_play_info(&bvid, cid, qn)
        .await
        .map_err(|e| e.to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let data_dir = app
                .path()
                .app_data_dir()
                .unwrap_or_else(|_| std::env::temp_dir().join("purebili"));
            let session_path = data_dir.join("session.json");
            let client = Arc::new(
                BiliClient::new(session_path).expect("Failed to initialize BiliClient"),
            );
            let dlna = Arc::new(DlnaManager::new(Arc::clone(&client)));

            let client_clone = Arc::clone(&client);
            let handle = app.handle().clone();
            tauri::async_runtime::block_on(async move {
                let _ = proxy::start_proxy_server(client_clone, handle).await;
            });

            app.manage(AppState { client, dlna });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_profile,
            logout,
            qrcode_generate,
            qrcode_poll,
            get_password_captcha,
            open_geetest_window,
            login_with_password,
            get_followings,
            search_followings,
            get_following_tags,
            get_tag_followings,
            unfollow_up,
            discover_dlna_devices,
            cast_video_to_dlna,
            control_dlna,
            get_following_feed,
            get_up_videos,
            get_fav_folders,
            get_video_fav_folders,
            unfavorite_video,
            get_fav_videos,
            get_video_detail,
            get_play_info,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
