# PureBili — 纯净 B 站客户端（仅看关注 UP 主与收藏夹）

无首页推荐、无热门榜单、无广告推广、无直播商城入口。每个端均可**完全独立运行**，无需电脑或手机做中转服务器。

## 支持平台与架构

| 平台 | 技术栈 | 说明 |
| --- | --- | --- |
| **Windows 桌面端** | Tauri 2.0 + React + Rust | 原生桌面客户端，内置 Rust 本地流媒体代理与 Wbi 签名 |
| **Android 手机 & 32 位老电视** | 原生 Kotlin + Media3/ExoPlayer | **`minSdk 21` (Android 5.0+)**，支持 **`armeabi-v7a` (32 位小米电视等)** 与 `arm64-v8a`，自适应 TV 遥控器与手机触屏双模式 |

## 核心功能

1. **双方式登录（官方流程）**：
   - **扫码登录**：B 站手机客户端扫码确认登录（TV 端默认推荐）。
   - **账号密码登录**：RSA 公钥加密 + 内置官方极验（Geetest）安全验证窗口，验证完成后自动登录。
2. **仅限关注与收藏（支持取消关注与取消收藏）**：
   - **关注 UP 主**：支持查看全部关注 UP 主最新投稿视频流，或点击单个 UP 主仅看 TA 的视频，并支持二次确认后一键**取消关注**（列表顶部与播放器内均支持）。
   - **我的收藏夹**：支持查看全部自建与订阅收藏夹内的视频，并支持在收藏夹视频卡片或播放器内二次确认后**取消收藏**。
3. **高清流畅、老设备兼容与 DLNA 局域网投屏**：
   - 支持 **DASH 高清流（1080P/4K，优先 H.264 硬件解码）** 与 **MP4 单流硬件兼容模式** 一键切换（老旧 32 位电视自动降级或手动切换 MP4 极度省内存）。
   - **局域网投屏与电视端投屏接收**：
     - **发送投屏**：Windows 桌面端与 Android 手机端均内置 SSDP/DLNA 投屏发现与一次性 Token 流媒体代理，自动注入 B 站 `Referer` 防盗链头。
     - **电视端接收投屏**：Android 电视端（TV 模式默认自动开启，亦可在顶栏「投屏接收:开/关」切换）内置标准 DLNA MediaRenderer 接收端，支持接收 **手机哔哩哔哩官方 App（DLNA 列表）**、PureBili 手机/桌面端以及其他 DLNA App 的投屏推送，并使用内置 ExoPlayer 硬件解码及进度/音量同步。
   - 支持画质切换、0.5x–2.0x 倍速、多 P 分集、自动连播、桌面快捷键与电视遥控器方向键快进/快退。

## 本地构建说明

### 1. Windows 客户端 (`desktop/`)

- **免编译直接启动（已内置）**：本机无需安装 Visual Studio C++ Build Tools，直接双击根目录的 [`start-windows.bat`](start-windows.bat)，或在 `desktop/` 目录运行：
  ```powershell
  cd desktop
  npm run build
  npm run start:win
  ```
  会自动启动本地加密/流媒体代理服务并用 Edge 独立应用窗口打开 PureBili。

- **打包原生 Tauri 2.0 `.exe` / `.msi`（需安装 VS C++ Build Tools）**：
  ```powershell
  cd desktop
  npm install
  npm run tauri dev
  npm run tauri build
  ```

### 2. Android 手机 & 32 位小米电视 (`android/`)

本工程支持 Android 5.0+ 的 32 位（`armeabi-v7a`）与 64 位（`arm64-v8a`）设备，会在构建时拆分出独立架构的 APK 以及通用 APK：

1. 安装 Android Studio（或 Android SDK Command-line Tools，JDK 17）。
2. 打开 `android/` 目录或命令行执行：
   ```bash
   cd android
   gradle assembleRelease
   ```
3. 产物位置：`android/app/build/outputs/apk/release/`
   - 32 位老小米电视请安装 `app-armeabi-v7a-release.apk`（体积最小，拷贝到 U 盘在电视「小米电视设置 -> 账号与安全 -> 允许安装未知来源应用」后直接安装）。
   - 现代手机可安装 `app-arm64-v8a-release.apk` 或通用包 `app-universal-release.apk`。
4. 也可以直接推送到 GitHub 仓库，触发 `.github/workflows/build.yml` 自动打包下载双端产物。
