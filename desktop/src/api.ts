import { invoke } from "@tauri-apps/api/core";
import type {
  CaptchaInfo,
  CastResult,
  DlnaDevice,
  FavFolder,
  FeedPage,
  FollowingPage,
  FollowingTag,
  PasswordLoginResult,
  PlayInfo,
  QrCodeInfo,
  QrPollResult,
  UserProfile,
  VideoDetail,
} from "./types";

export const isTauriEnv = (): boolean =>
  typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

async function httpJson<T>(url: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(url, init);
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(text || `HTTP ${resp.status}`);
  }
  return resp.json() as Promise<T>;
}

export const api = {
  getProfile: () =>
    isTauriEnv()
      ? invoke<UserProfile>("get_profile")
      : httpJson<UserProfile>("/api/profile"),

  logout: () =>
    isTauriEnv()
      ? invoke<void>("logout")
      : httpJson<void>("/api/logout", { method: "POST" }),

  qrcodeGenerate: () =>
    isTauriEnv()
      ? invoke<QrCodeInfo>("qrcode_generate")
      : httpJson<QrCodeInfo>("/api/qrcode/generate"),

  qrcodePoll: (qrcodeKey: string) =>
    isTauriEnv()
      ? invoke<QrPollResult>("qrcode_poll", { qrcodeKey })
      : httpJson<QrPollResult>(
          `/api/qrcode/poll?qrcode_key=${encodeURIComponent(qrcodeKey)}`
        ),

  getPasswordCaptcha: () =>
    isTauriEnv()
      ? invoke<CaptchaInfo>("get_password_captcha")
      : httpJson<CaptchaInfo>("/api/password/captcha"),

  openGeetestWindow: (token: string, gt: string, challenge: string) => {
    if (isTauriEnv()) {
      return invoke<void>("open_geetest_window", { token, gt, challenge });
    }
    const url = `/geetest?token=${encodeURIComponent(token)}&gt=${encodeURIComponent(
      gt
    )}&challenge=${encodeURIComponent(challenge)}`;
    window.open(url, "PureBiliGeetest", "width=420,height=480");
    return Promise.resolve();
  },

  loginWithPassword: (params: {
    username: string;
    password: string;
    token: string;
    challenge: string;
    validate: string;
    seccode: string;
  }) =>
    isTauriEnv()
      ? invoke<PasswordLoginResult>("login_with_password", params)
      : httpJson<PasswordLoginResult>("/api/password/login", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(params),
        }),

  getFollowings: (
    pn: number,
    ps: number,
    options?: { keyword?: string; tagid?: number | null }
  ) => {
    if (isTauriEnv()) {
      if (options?.keyword?.trim()) {
        return invoke<FollowingPage>("search_followings", {
          name: options.keyword.trim(),
          pn,
          ps,
        });
      }
      if (options?.tagid !== undefined && options?.tagid !== null) {
        return invoke<FollowingPage>("get_tag_followings", {
          tagid: options.tagid,
          pn,
          ps,
        });
      }
      return invoke<FollowingPage>("get_followings", { pn, ps });
    }
    const qs = new URLSearchParams({ pn: String(pn), ps: String(ps) });
    if (options?.keyword?.trim()) qs.set("keyword", options.keyword.trim());
    if (options?.tagid !== undefined && options?.tagid !== null) {
      qs.set("tagid", String(options.tagid));
    }
    return httpJson<FollowingPage>(`/api/followings?${qs.toString()}`);
  },

  getFollowingTags: () =>
    isTauriEnv()
      ? invoke<FollowingTag[]>("get_following_tags")
      : httpJson<FollowingTag[]>("/api/following/tags"),

  unfollowUp: (mid: number) =>
    isTauriEnv()
      ? invoke<void>("unfollow_up", { mid })
      : httpJson<{ ok: boolean }>("/api/unfollow", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ mid }),
        }).then(() => undefined),

  discoverDlnaDevices: () =>
    isTauriEnv()
      ? invoke<DlnaDevice[]>("discover_dlna_devices")
      : httpJson<DlnaDevice[]>("/api/dlna/devices"),

  castVideoToDlna: (params: {
    controlUrl: string;
    bvid: string;
    cid: number;
    qn: number;
    title: string;
  }) =>
    isTauriEnv()
      ? invoke<CastResult>("cast_video_to_dlna", {
          controlUrl: params.controlUrl,
          bvid: params.bvid,
          cid: params.cid,
          qn: params.qn,
          title: params.title,
        })
      : httpJson<CastResult>("/api/dlna/cast", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            control_url: params.controlUrl,
            bvid: params.bvid,
            cid: params.cid,
            qn: params.qn,
            title: params.title,
          }),
        }),

  controlDlna: (controlUrl: string, command: "play" | "pause" | "stop") =>
    isTauriEnv()
      ? invoke<void>("control_dlna", { controlUrl, command })
      : httpJson<{ ok: boolean }>("/api/dlna/control", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ control_url: controlUrl, command }),
        }).then(() => undefined),

  getFollowingFeed: (offset: string) =>
    isTauriEnv()
      ? invoke<FeedPage>("get_following_feed", { offset })
      : httpJson<FeedPage>(
          `/api/following/feed?offset=${encodeURIComponent(offset)}`
        ),

  getUpVideos: (mid: number, pn: number, ps: number) =>
    isTauriEnv()
      ? invoke<FeedPage>("get_up_videos", { mid, pn, ps })
      : httpJson<FeedPage>(`/api/up/videos?mid=${mid}&pn=${pn}&ps=${ps}`),

  getFavFolders: () =>
    isTauriEnv()
      ? invoke<FavFolder[]>("get_fav_folders")
      : httpJson<FavFolder[]>("/api/fav/folders"),

  getVideoFavFolders: (aid: number) =>
    isTauriEnv()
      ? invoke<FavFolder[]>("get_video_fav_folders", { aid })
      : httpJson<FavFolder[]>(`/api/fav/video_folders?aid=${aid}`),

  unfavoriteVideo: (aid: number, mediaIds: number[]) =>
    isTauriEnv()
      ? invoke<void>("unfavorite_video", { aid, mediaIds })
      : httpJson<{ ok: boolean }>("/api/fav/unfavorite", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ aid, media_ids: mediaIds }),
        }).then(() => undefined),

  getFavVideos: (mediaId: number, pn: number, ps: number, keyword: string) =>
    isTauriEnv()
      ? invoke<FeedPage>("get_fav_videos", { mediaId, pn, ps, keyword })
      : httpJson<FeedPage>(
          `/api/fav/videos?media_id=${mediaId}&pn=${pn}&ps=${ps}&keyword=${encodeURIComponent(
            keyword
          )}`
        ),

  getVideoDetail: (bvid: string) =>
    isTauriEnv()
      ? invoke<VideoDetail>("get_video_detail", { bvid })
      : httpJson<VideoDetail>(
          `/api/video/detail?bvid=${encodeURIComponent(bvid)}`
        ),

  getPlayInfo: (bvid: string, cid: number, qn: number) =>
    isTauriEnv()
      ? invoke<PlayInfo>("get_play_info", { bvid, cid, qn })
      : httpJson<PlayInfo>(
          `/api/video/play?bvid=${encodeURIComponent(bvid)}&cid=${cid}&qn=${qn}`
        ),
};
