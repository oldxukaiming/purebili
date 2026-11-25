export interface UserProfile {
  is_logged_in: boolean;
  uid: number;
  uname: string;
  face: string;
}

export interface QrCodeInfo {
  url: string;
  qrcode_key: string;
}

export interface QrPollResult {
  status: number;
  message: string;
  logged_in: boolean;
}

export interface CaptchaInfo {
  token: string;
  gt: string;
  challenge: string;
}

export interface GeetestVerifyPayload {
  token: string;
  challenge: string;
  validate: string;
  seccode: string;
}

export interface PasswordLoginResult {
  success: boolean;
  message: string;
  verify_url: string | null;
}

export interface FollowingUp {
  mid: number;
  uname: string;
  face: string;
  sign: string;
}

export interface FollowingPage {
  list: FollowingUp[];
  total: number;
  has_more: boolean;
}

export interface FollowingTag {
  tagid: number;
  name: string;
  count: number;
}

export interface VideoItem {
  bvid: string;
  aid: number;
  title: string;
  cover: string;
  duration_text: string;
  author_name: string;
  author_mid: number;
  author_face: string;
  pub_text: string;
  play_count: string;
  invalid: boolean;
}

export interface FeedPage {
  items: VideoItem[];
  next_offset: string;
  has_more: boolean;
}

export interface FavFolder {
  id: number;
  title: string;
  media_count: number;
  is_collected: boolean;
  fav_state?: number;
}

export interface VideoPagePart {
  cid: number;
  page: number;
  part: string;
  duration: number;
}

export interface VideoDetail {
  bvid: string;
  aid: number;
  title: string;
  desc: string;
  cover: string;
  author_name: string;
  author_mid: number;
  author_face: string;
  pages: VideoPagePart[];
}

export interface QualityOption {
  qn: number;
  desc: string;
}

export interface PlayInfo {
  bvid: string;
  cid: number;
  current_qn: number;
  qualities: QualityOption[];
  dash_mpd_url: string | null;
  mp4_url: string | null;
}

export interface DlnaDevice {
  id: string;
  name: string;
  host: string;
  location: string;
  control_url: string;
}

export interface CastResult {
  cast_url: string;
}
