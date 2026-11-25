import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { Readable } from "node:stream";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import {
  castToDevice,
  controlDlnaDevice,
  discoverDlnaDevices,
} from "./dlna.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST_DIR = path.join(__dirname, "dist");
let sessionDir =
  process.env.PUREBILI_SESSION_DIR ||
  path.join(process.env.LOCALAPPDATA || __dirname, "PureBili");
let sessionPath = path.join(sessionDir, "session.json");

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
const REFERER = "https://www.bilibili.com/";

const MIXIN_KEY_ENC_TAB = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
  33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
  26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
  20, 34, 44, 52,
];

let session = loadSession();
const dashCache = new Map();
const dynamicAllowedHosts = new Set();
let serverPort = 0;

function loadSession() {
  try {
    if (fs.existsSync(sessionPath)) {
      return JSON.parse(fs.readFileSync(sessionPath, "utf8"));
    }
  } catch {}
  return {
    cookies: {},
    uid: 0,
    uname: "",
    face: "",
    wbiImgKey: "",
    wbiSubKey: "",
    wbiUpdatedAt: 0,
  };
}

function saveSession() {
  try {
    fs.mkdirSync(sessionDir, { recursive: true });
    fs.writeFileSync(sessionPath, JSON.stringify(session, null, 2), "utf8");
  } catch {}
}

function cookieHeader() {
  return Object.entries(session.cookies)
    .map(([k, v]) => `${k}=${v}`)
    .join("; ");
}

function storeSetCookies(headers) {
  const raw =
    typeof headers.getSetCookie === "function" ? headers.getSetCookie() : [];
  let changed = false;
  for (const item of raw) {
    const kv = item.split(";")[0];
    const eq = kv.indexOf("=");
    if (eq > 0) {
      const k = kv.slice(0, eq).trim();
      const v = kv.slice(eq + 1).trim();
      if (k && v) {
        session.cookies[k] = v;
        changed = true;
      }
    }
  }
  if (changed) saveSession();
}

function registerAllowedUrl(rawUrl) {
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") {
      const h = parsed.hostname.toLowerCase();
      if (
        h !== "localhost" &&
        !h.startsWith("127.") &&
        !h.startsWith("10.") &&
        !h.startsWith("192.168.") &&
        !h.startsWith("169.254.")
      ) {
        dynamicAllowedHosts.add(h);
      }
    }
  } catch {}
}

function pickBestStreamUrl(item) {
  const candidates = [
    item?.baseUrl,
    item?.base_url,
    item?.url,
    ...(Array.isArray(item?.backupUrl) ? item.backupUrl : []),
    ...(Array.isArray(item?.backup_url) ? item.backup_url : []),
  ].filter(Boolean);
  const official = candidates.find(
    (u) => u.includes("bilivideo.com") || u.includes("bilivideo.cn")
  );
  const chosen = official || candidates[0] || "";
  if (chosen) registerAllowedUrl(chosen);
  return chosen;
}

function proxyImageUrl(raw) {
  if (!raw) return "";
  let normalized = raw;
  if (raw.startsWith("//")) normalized = `https:${raw}`;
  else if (raw.startsWith("http://")) normalized = raw.replace("http://", "https://");
  registerAllowedUrl(normalized);
  return `/proxy?url=${encodeURIComponent(normalized)}`;
}

async function biliFetchJson(url, options = {}) {
  const headers = {
    "User-Agent": UA,
    Referer: REFERER,
    Origin: "https://www.bilibili.com",
    Accept: "application/json, text/plain, */*",
    "Accept-Language": "zh-CN,zh;q=0.9",
    ...(options.headers || {}),
  };
  const cookie = cookieHeader();
  if (cookie) headers.Cookie = cookie;
  const resp = await fetch(url, { ...options, headers });
  storeSetCookies(resp.headers);
  const text = await resp.text();
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`B站接口返回非 JSON (HTTP ${resp.status})`);
  }
}

async function ensureBuvid() {
  if (session.cookies.buvid3 && session.cookies.buvid4 && session.cookies.b_nut) return;
  try {
    const home = await fetch("https://www.bilibili.com/", {
      headers: { "User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9" },
    });
    storeSetCookies(home.headers);
  } catch {}
  try {
    const val = await biliFetchJson(
      "https://api.bilibili.com/x/frontend/finger/spi"
    );
    if (val?.data?.b_3) session.cookies.buvid3 = val.data.b_3;
    if (val?.data?.b_4) session.cookies.buvid4 = val.data.b_4;
    saveSession();
  } catch {}
}

function extractWbiKey(u) {
  return (u || "").split("/").pop().split(".")[0] || "";
}

async function getProfile() {
  await ensureBuvid();
  const val = await biliFetchJson("https://api.bilibili.com/x/web-interface/nav");
  const data = val?.data || {};
  if (data.wbi_img) {
    const img = extractWbiKey(data.wbi_img.img_url);
    const sub = extractWbiKey(data.wbi_img.sub_url);
    if (img && sub) {
      session.wbiImgKey = img;
      session.wbiSubKey = sub;
      session.wbiUpdatedAt = Math.floor(Date.now() / 1000);
    }
  }
  if (data.isLogin) {
    session.uid = data.mid || 0;
    session.uname = data.uname || "B站用户";
    session.face = data.face || "";
    saveSession();
    return {
      is_logged_in: true,
      uid: session.uid,
      uname: session.uname,
      face: proxyImageUrl(session.face),
    };
  }
  saveSession();
  return { is_logged_in: false, uid: 0, uname: "", face: "" };
}

async function signWbiQuery(params) {
  const now = Math.floor(Date.now() / 1000);
  if (
    !session.wbiImgKey ||
    !session.wbiSubKey ||
    now - (session.wbiUpdatedAt || 0) > 3600
  ) {
    await getProfile();
  }
  const raw = `${session.wbiImgKey}${session.wbiSubKey}`;
  const mixinKey = MIXIN_KEY_ENC_TAB.slice(0, 32)
    .map((idx) => raw[idx] || "")
    .join("");
  const merged = { ...params, wts: String(now) };
  const query = Object.keys(merged)
    .sort()
    .map((k) => {
      const clean = String(merged[k]).replace(/[!'()*]/g, "");
      return `${encodeURIComponent(k)}=${encodeURIComponent(clean)}`;
    })
    .join("&");
  const wRid = crypto
    .createHash("md5")
    .update(query + mixinKey)
    .digest("hex");
  return `${query}&w_rid=${wRid}`;
}

export function isAllowedBilibiliHost(rawUrl) {
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return false;
    const h = parsed.hostname.toLowerCase();
    const suffixes = [
      "bilivideo.com",
      "bilivideo.cn",
      "hdslb.com",
      "bilibili.com",
      "akamaized.net",
      "szbdyd.com",
      "acgvideo.com",
      "biliapi.net",
    ];
    if (dynamicAllowedHosts.has(h)) return true;
    return suffixes.some((s) => h === s || h.endsWith(`.${s}`));
  } catch {
    return false;
  }
}

function xmlEscape(str) {
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function formatDuration(secs) {
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = secs % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

function formatCount(n) {
  return n >= 10000 ? `${(n / 10000).toFixed(1)}万` : String(n || 0);
}

function readBodyJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}"));
      } catch (e) {
        reject(e);
      }
    });
    req.on("error", reject);
  });
}

function sendJson(res, data, status = 200) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(data));
}

function serveStatic(res, pathname) {
  const rel = pathname === "/" ? "/index.html" : pathname;
  const target = path.normalize(path.join(DIST_DIR, rel));
  if (!target.startsWith(DIST_DIR) || !fs.existsSync(target) || fs.statSync(target).isDirectory()) {
    const fallback = path.join(DIST_DIR, "index.html");
    if (fs.existsSync(fallback)) {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      fs.createReadStream(fallback).pipe(res);
    } else {
      res.writeHead(404);
      res.end("dist not built. Run npm run build first.");
    }
    return;
  }
  const ext = path.extname(target);
  const mimeMap = {
    ".html": "text/html; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
  };
  res.writeHead(200, { "Content-Type": mimeMap[ext] || "application/octet-stream" });
  fs.createReadStream(target).pipe(res);
}

export function createPureBiliServer(options = {}) {
  if (options.sessionDir) {
    sessionDir = options.sessionDir;
    sessionPath = path.join(sessionDir, "session.json");
    session = loadSession();
  }
  return http.createServer(async (req, res) => {
    try {
      const u = new URL(req.url || "/", `http://127.0.0.1:${serverPort || 80}`);
      const p = u.pathname;

      if (p === "/api/profile") {
        return sendJson(res, await getProfile());
      }
      if (p === "/api/logout" && req.method === "POST") {
        session.cookies = {};
        session.uid = 0;
        session.uname = "";
        session.face = "";
        saveSession();
        await ensureBuvid();
        return sendJson(res, { ok: true });
      }
      if (p === "/api/qrcode/generate") {
        await ensureBuvid();
        const val = await biliFetchJson(
          "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        );
        if (val.code !== 0) return sendJson(res, val.message || "获取二维码失败", 500);
        return sendJson(res, {
          url: val.data.url,
          qrcode_key: val.data.qrcode_key,
        });
      }
      if (p === "/api/qrcode/poll") {
        const key = u.searchParams.get("qrcode_key") || "";
        const val = await biliFetchJson(
          `https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=${encodeURIComponent(key)}`
        );
        const code = val?.data?.code ?? -1;
        if (code === 0) {
          await getProfile();
          return sendJson(res, { status: 0, message: "登录成功", logged_in: true });
        }
        return sendJson(res, {
          status: code,
          message: val?.data?.message || "",
          logged_in: false,
        });
      }
      if (p === "/api/password/captcha") {
        await ensureBuvid();
        const val = await biliFetchJson(
          "https://passport.bilibili.com/x/passport-login/captcha?source=main_web"
        );
        if (val.code !== 0) return sendJson(res, val.message || "获取验证参数失败", 500);
        return sendJson(res, {
          token: val.data.token,
          gt: val.data.geetest.gt,
          challenge: val.data.geetest.challenge,
        });
      }
      if (p === "/api/password/login" && req.method === "POST") {
        const body = await readBodyJson(req);
        const keyVal = await biliFetchJson(
          "https://passport.bilibili.com/x/passport-login/web/key"
        );
        if (keyVal.code !== 0) return sendJson(res, { success: false, message: "获取公钥失败", verify_url: null });
        const encrypted = crypto
          .publicEncrypt(
            {
              key: keyVal.data.key,
              padding: crypto.constants.RSA_PKCS1_PADDING,
            },
            Buffer.from(`${keyVal.data.hash}${body.password}`, "utf8")
          )
          .toString("base64");
        const form = new URLSearchParams({
          username: body.username,
          password: encrypted,
          keep: "0",
          token: body.token,
          challenge: body.challenge,
          validate: body.validate,
          seccode: body.seccode,
          source: "main_web",
          go_url: "https://www.bilibili.com",
        });
        const loginRes = await biliFetchJson(
          "https://passport.bilibili.com/x/passport-login/web/login",
          {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: form.toString(),
          }
        );
        if (loginRes.code !== 0) {
          return sendJson(res, {
            success: false,
            message: loginRes.message || "登录失败",
            verify_url: null,
          });
        }
        if ((loginRes.data?.status ?? 0) === 0) {
          await getProfile();
          return sendJson(res, { success: true, message: "登录成功", verify_url: null });
        }
        return sendJson(res, {
          success: false,
          message: loginRes.data?.message || "需要二次验证，建议使用扫码登录",
          verify_url: loginRes.data?.url || null,
        });
      }
      if (p === "/api/followings") {
        if (!session.uid) await getProfile();
        const pn = Math.max(1, Number(u.searchParams.get("pn") || "1"));
        const ps = Math.min(50, Math.max(1, Number(u.searchParams.get("ps") || "50")));
        const keyword = (u.searchParams.get("keyword") || "").trim();
        const tagidRaw = u.searchParams.get("tagid");

        if (keyword) {
          const val = await biliFetchJson(
            `https://api.bilibili.com/x/relation/followings/search?vmid=${session.uid}&name=${encodeURIComponent(keyword)}&pn=${pn}&ps=${ps}`
          );
          if (val.code !== 0) return sendJson(res, { error: val.message || "搜索关注失败" }, 400);
          const list = (val?.data?.list || []).map((x) => ({
            mid: x.mid,
            uname: x.uname,
            face: proxyImageUrl(x.face),
            sign: x.sign || "",
          }));
          const total = Number(val?.data?.total ?? list.length);
          const has_more = list.length > 0 && (pn - 1) * ps + list.length < total;
          return sendJson(res, { list, total, has_more });
        }

        if (tagidRaw !== null && tagidRaw !== "") {
          const tagid = Number(tagidRaw);
          const val = await biliFetchJson(
            `https://api.bilibili.com/x/relation/tag?mid=${session.uid}&tagid=${tagid}&pn=${pn}&ps=${ps}`
          );
          if (val.code !== 0) return sendJson(res, { error: val.message || "获取分组关注失败" }, 400);
          const rawList = Array.isArray(val?.data) ? val.data : [];
          const list = rawList.map((x) => ({
            mid: x.mid,
            uname: x.uname,
            face: proxyImageUrl(x.face),
            sign: x.sign || "",
          }));
          return sendJson(res, {
            list,
            total: list.length,
            has_more: list.length >= ps,
          });
        }

        const val = await biliFetchJson(
          `https://api.bilibili.com/x/relation/followings?vmid=${session.uid}&pn=${pn}&ps=${ps}&order=desc`
        );
        if (val.code !== 0) return sendJson(res, { error: val.message || "获取关注列表失败" }, 400);
        const list = (val?.data?.list || []).map((x) => ({
          mid: x.mid,
          uname: x.uname,
          face: proxyImageUrl(x.face),
          sign: x.sign || "",
        }));
        const total = Number(val?.data?.total ?? list.length);
        const has_more = list.length > 0 && (pn - 1) * ps + list.length < total;
        return sendJson(res, { list, total, has_more });
      }
      if (p === "/api/following/tags") {
        const val = await biliFetchJson("https://api.bilibili.com/x/relation/tags");
        if (val.code !== 0) return sendJson(res, { error: val.message || "获取关注分组失败" }, 400);
        const tags = (Array.isArray(val?.data) ? val.data : []).map((t) => ({
          tagid: t.tagid,
          name: t.name,
          count: t.count || 0,
        }));
        return sendJson(res, tags);
      }
      if (p === "/api/unfollow" && req.method === "POST") {
        const body = await readBodyJson(req);
        const mid = Number(body.mid || 0);
        const csrf = session.cookies.bili_jct || "";
        if (!csrf) {
          return sendJson(res, { error: "登录凭据缺少 CSRF (bili_jct)，请重新登录" }, 400);
        }
        if (!mid) {
          return sendJson(res, { error: "无效的 UP 主 ID" }, 400);
        }
        const form = new URLSearchParams({
          fid: String(mid),
          act: "2",
          re_src: "11",
          csrf,
        });
        const val = await biliFetchJson("https://api.bilibili.com/x/relation/modify", {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: form.toString(),
        });
        if (val.code !== 0) {
          return sendJson(res, { error: val.message || "取消关注失败" }, 400);
        }
        return sendJson(res, { ok: true });
      }
      if (p === "/api/dlna/devices") {
        const devices = await discoverDlnaDevices();
        return sendJson(res, devices);
      }
      if (p === "/api/dlna/cast" && req.method === "POST") {
        await ensureBuvid();
        const body = await readBodyJson(req);
        const { control_url, bvid, cid, qn, title } = body;
        if (!control_url || !bvid || !cid) {
          return sendJson(res, { error: "缺少投屏参数" }, 400);
        }
        const mq = await signWbiQuery({
          bvid: String(bvid),
          cid: String(cid),
          qn: String(qn || 64),
          fnval: "1",
          fnver: "0",
        });
        const mVal = await biliFetchJson(
          `https://api.bilibili.com/x/player/wbi/playurl?${mq}`
        );
        if (mVal.code !== 0) {
          return sendJson(res, { error: mVal.message || "获取投屏流失败" }, 400);
        }
        const durlEntry = mVal?.data?.durl?.[0];
        const rawMp4 = durlEntry ? pickBestStreamUrl(durlEntry) : "";
        if (!rawMp4) {
          return sendJson(res, { error: "未解析到可用 MP4 投屏流" }, 404);
        }
        const result = await castToDevice({
          controlUrl: control_url,
          mp4Url: rawMp4,
          title: title || bvid,
        });
        return sendJson(res, result);
      }
      if (p === "/api/dlna/control" && req.method === "POST") {
        const body = await readBodyJson(req);
        await controlDlnaDevice(body.control_url, body.command);
        return sendJson(res, { ok: true });
      }
      if (p === "/api/following/feed") {
        const offset = u.searchParams.get("offset") || "";
        const target = offset
          ? `https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=video&offset=${encodeURIComponent(offset)}`
          : "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=video";
        const val = await biliFetchJson(target);
        if (val.code !== 0) return sendJson(res, val.message || "获取动态失败", 500);
        const items = [];
        for (const it of val?.data?.items || []) {
          const author = it?.modules?.module_author || {};
          const major = it?.modules?.module_dynamic?.major || {};
          if (major.type === "MAJOR_TYPE_ARCHIVE" && major.archive?.bvid) {
            const arc = major.archive;
            items.push({
              bvid: arc.bvid,
              aid: Number(arc.aid) || 0,
              title: arc.title || "",
              cover: proxyImageUrl(arc.cover),
              duration_text: arc.duration_text || "",
              author_name: author.name || "",
              author_mid: author.mid || 0,
              author_face: proxyImageUrl(author.face),
              pub_text: author.pub_time || "",
              play_count: arc.stat?.play || "",
              invalid: false,
            });
          }
        }
        return sendJson(res, {
          items,
          next_offset: val?.data?.offset || "",
          has_more: Boolean(val?.data?.has_more),
        });
      }
      if (p === "/api/up/videos") {
        const mid = u.searchParams.get("mid") || "0";
        const pn = Number(u.searchParams.get("pn") || "1");
        const ps = Number(u.searchParams.get("ps") || "24");
        const q = await signWbiQuery({ mid, pn: String(pn), ps: String(ps), order: "pubdate" });
        const val = await biliFetchJson(`https://api.bilibili.com/x/space/wbi/arc/search?${q}`);
        if (val.code !== 0) return sendJson(res, val.message || "获取UP主视频失败", 500);
        const vlist = val?.data?.list?.vlist || [];
        const count = val?.data?.page?.count || 0;
        const items = vlist.map((v) => ({
          bvid: v.bvid || "",
          aid: v.aid || 0,
          title: v.title || "",
          cover: proxyImageUrl(v.pic),
          duration_text: v.length || "",
          author_name: v.author || "",
          author_mid: v.mid || Number(mid),
          author_face: "",
          pub_text: "",
          play_count: formatCount(v.play),
          invalid: false,
        }));
        return sendJson(res, {
          items,
          next_offset: String(pn + 1),
          has_more: pn * ps < count,
        });
      }
      if (p === "/api/fav/folders") {
        if (!session.uid) await getProfile();
        const out = [];
        const created = await biliFetchJson(
          `https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=${session.uid}`
        );
        if (created.code === 0) {
          for (const f of created?.data?.list || []) {
            out.push({
              id: f.id,
              title: f.title,
              media_count: f.media_count,
              is_collected: false,
              fav_state: f.fav_state || 0,
            });
          }
        }
        const collected = await biliFetchJson(
          `https://api.bilibili.com/x/v3/fav/folder/collected/list?up_mid=${session.uid}&pn=1&ps=50`
        );
        if (collected.code === 0) {
          for (const f of collected?.data?.list || []) {
            out.push({
              id: f.id,
              title: `${f.title} (订阅)`,
              media_count: f.media_count,
              is_collected: true,
              fav_state: 0,
            });
          }
        }
        return sendJson(res, out);
      }
      if (p === "/api/fav/video_folders") {
        if (!session.uid) await getProfile();
        const aid = Number(u.searchParams.get("aid") || "0");
        if (!aid) return sendJson(res, { error: "缺少视频 aid" }, 400);
        const val = await biliFetchJson(
          `https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=${session.uid}&rid=${aid}&type=2`
        );
        if (val.code !== 0) return sendJson(res, { error: val.message || "查询收藏状态失败" }, 400);
        const list = (val?.data?.list || []).map((f) => ({
          id: f.id,
          title: f.title,
          media_count: f.media_count,
          is_collected: false,
          fav_state: f.fav_state || 0,
        }));
        return sendJson(res, list);
      }
      if (p === "/api/fav/unfavorite" && req.method === "POST") {
        const body = await readBodyJson(req);
        const aid = Number(body.aid || 0);
        const mediaIds = Array.isArray(body.media_ids) ? body.media_ids.map(Number).filter(Boolean) : [];
        const csrf = session.cookies.bili_jct || "";
        if (!csrf) {
          return sendJson(res, { error: "登录凭据缺少 CSRF (bili_jct)，请重新登录" }, 400);
        }
        if (!aid || mediaIds.length === 0) {
          return sendJson(res, { error: "缺少视频 aid 或收藏夹 ID" }, 400);
        }
        const form = new URLSearchParams({
          rid: String(aid),
          type: "2",
          add_media_ids: "",
          del_media_ids: mediaIds.join(","),
          csrf,
        });
        const val = await biliFetchJson("https://api.bilibili.com/x/v3/fav/resource/deal", {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: form.toString(),
        });
        if (val.code !== 0) {
          return sendJson(res, { error: val.message || "取消收藏失败" }, 400);
        }
        return sendJson(res, { ok: true });
      }
      if (p === "/api/fav/videos") {
        const mediaId = u.searchParams.get("media_id") || "0";
        const pn = Number(u.searchParams.get("pn") || "1");
        const ps = Number(u.searchParams.get("ps") || "20");
        const kw = u.searchParams.get("keyword") || "";
        const val = await biliFetchJson(
          `https://api.bilibili.com/x/v3/fav/resource/list?media_id=${mediaId}&pn=${pn}&ps=${ps}&keyword=${encodeURIComponent(kw)}&platform=web`
        );
        if (val.code !== 0) return sendJson(res, val.message || "获取收藏夹失败", 500);
        const items = (val?.data?.medias || [])
          .filter((m) => (m.type ?? 2) === 2)
          .map((m) => ({
            bvid: m.bvid || "",
            aid: m.id || 0,
            title: m.title || "",
            cover: proxyImageUrl(m.cover),
            duration_text: formatDuration(m.duration || 0),
            author_name: m.upper?.name || "",
            author_mid: m.upper?.mid || 0,
            author_face: proxyImageUrl(m.upper?.face),
            pub_text: "",
            play_count: formatCount(m.cnt_info?.play),
            invalid: m.attr === 9 || m.title === "已失效视频",
          }));
        return sendJson(res, {
          items,
          next_offset: String(pn + 1),
          has_more: Boolean(val?.data?.has_more),
        });
      }
      if (p === "/api/video/detail") {
        await ensureBuvid();
        const bvid = u.searchParams.get("bvid") || "";
        const val = await biliFetchJson(
          `https://api.bilibili.com/x/web-interface/view?bvid=${encodeURIComponent(bvid)}`
        );
        if (val.code !== 0) return sendJson(res, val.message || "获取详情失败", 500);
        const d = val.data;
        return sendJson(res, {
          bvid: d.bvid,
          aid: d.aid || 0,
          title: d.title,
          desc: d.desc || "",
          cover: proxyImageUrl(d.pic),
          author_name: d.owner?.name || "",
          author_mid: d.owner?.mid || 0,
          author_face: proxyImageUrl(d.owner?.face),
          pages: (d.pages || []).map((pg) => ({
            cid: pg.cid,
            page: pg.page,
            part: pg.part || "正片",
            duration: pg.duration || 0,
          })),
        });
      }
      if (p === "/api/video/play") {
        const bvid = u.searchParams.get("bvid") || "";
        const cid = u.searchParams.get("cid") || "0";
        const qn = Number(u.searchParams.get("qn") || "80");
        const dq = await signWbiQuery({ bvid, cid, qn: String(qn), fnval: "4048", fnver: "0", fourk: "1" });
        const dVal = await biliFetchJson(`https://api.bilibili.com/x/player/wbi/playurl?${dq}`);
        if (dVal.code !== 0) return sendJson(res, dVal.message || "获取播放流失败", 500);
        const data = dVal.data;
        const qualities = (data.accept_quality || []).map((q, i) => ({
          qn: q,
          desc: data.accept_description?.[i] || String(q),
        }));
        let dashMpdUrl = null;
        if (data.dash?.video?.length && data.dash?.audio?.length) {
          for (const v of data.dash.video) {
            v._chosenUrl = pickBestStreamUrl(v);
          }
          for (const a of data.dash.audio) {
            a._chosenUrl = pickBestStreamUrl(a);
          }
          dashCache.set(`${bvid}_${cid}_${qn}`, data.dash);
          dashMpdUrl = `/dash.mpd?bvid=${encodeURIComponent(bvid)}&cid=${cid}&qn=${qn}`;
        }
        let mp4Url = null;
        try {
          const mq = await signWbiQuery({ bvid, cid, qn: String(qn), fnval: "1", fnver: "0" });
          const mVal = await biliFetchJson(`https://api.bilibili.com/x/player/wbi/playurl?${mq}`);
          const durlEntry = mVal?.data?.durl?.[0];
          const rawMp4 = durlEntry ? pickBestStreamUrl(durlEntry) : "";
          if (rawMp4) mp4Url = `/proxy?url=${encodeURIComponent(rawMp4)}`;
        } catch {}
        return sendJson(res, {
          bvid,
          cid: Number(cid),
          current_qn: data.quality || qn,
          qualities,
          dash_mpd_url: dashMpdUrl,
          mp4_url: mp4Url,
        });
      }
      if (p === "/dash.mpd") {
        const bvid = u.searchParams.get("bvid") || "";
        const cid = u.searchParams.get("cid") || "0";
        const qn = Number(u.searchParams.get("qn") || "80");
        const dash = dashCache.get(`${bvid}_${cid}_${qn}`);
        if (!dash) {
          res.writeHead(404);
          return res.end("MPD not found");
        }
        const videos = dash.video || [];
        const pickedVideo =
          videos.find((v) => v.id === qn && (v.codecs || "").startsWith("avc")) ||
          videos.find((v) => (v.codecs || "").startsWith("avc")) ||
          videos[0];
        const bestAudio = [...(dash.audio || [])].sort(
          (a, b) => (b.bandwidth || 0) - (a.bandwidth || 0)
        )[0];

        const baseOrigin = `http://127.0.0.1:${serverPort}`;
        const vUrl = `${baseOrigin}/proxy?url=${encodeURIComponent(pickedVideo._chosenUrl || pickedVideo.baseUrl || pickedVideo.base_url)}`;
        const aUrl = `${baseOrigin}/proxy?url=${encodeURIComponent(bestAudio._chosenUrl || bestAudio.baseUrl || bestAudio.base_url)}`;
        const durationS = Math.max(1, dash.duration || 1);
        const mpd = `<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT${durationS}S" minBufferTime="PT1.5S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
  <Period id="0" start="PT0S">
    <AdaptationSet id="1" contentType="video" segmentAlignment="true" subsegmentAlignment="true">
      <Representation id="v_${pickedVideo.id}" bandwidth="${pickedVideo.bandwidth || 500000}" codecs="${xmlEscape(pickedVideo.codecs)}" mimeType="${xmlEscape(pickedVideo.mimeType || "video/mp4")}" width="${pickedVideo.width || 1280}" height="${pickedVideo.height || 720}" frameRate="${xmlEscape(pickedVideo.frameRate || "30")}">
        <BaseURL>${xmlEscape(vUrl)}</BaseURL>
        <SegmentBase indexRange="${xmlEscape(pickedVideo.SegmentBase?.indexRange || "1000-2000")}">
          <Initialization range="${xmlEscape(pickedVideo.SegmentBase?.Initialization || "0-999")}"/>
        </SegmentBase>
      </Representation>
    </AdaptationSet>
    <AdaptationSet id="2" contentType="audio" segmentAlignment="true" subsegmentAlignment="true">
      <Representation id="a_${bestAudio.id}" bandwidth="${bestAudio.bandwidth || 64000}" codecs="${xmlEscape(bestAudio.codecs)}" mimeType="${xmlEscape(bestAudio.mimeType || "audio/mp4")}">
        <BaseURL>${xmlEscape(aUrl)}</BaseURL>
        <SegmentBase indexRange="${xmlEscape(bestAudio.SegmentBase?.indexRange || "1000-2000")}">
          <Initialization range="${xmlEscape(bestAudio.SegmentBase?.Initialization || "0-999")}"/>
        </SegmentBase>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>`;
        res.writeHead(200, {
          "Content-Type": "application/dash+xml; charset=utf-8",
          "Access-Control-Allow-Origin": "*",
        });
        return res.end(mpd);
      }
      if (p === "/proxy") {
        const targetUrl = u.searchParams.get("url") || "";
        if (!isAllowedBilibiliHost(targetUrl)) {
          res.writeHead(403);
          return res.end("Forbidden host");
        }
        const outHeaders = {
          "User-Agent": UA,
          Referer: REFERER,
          Origin: "https://www.bilibili.com",
        };
        if (req.headers.range) outHeaders.Range = req.headers.range;
        const up = await fetch(targetUrl, { headers: outHeaders });
        const respHeaders = {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Expose-Headers": "Content-Length, Content-Range, Accept-Ranges",
        };
        for (const h of ["content-type", "content-length", "content-range", "accept-ranges", "cache-control"]) {
          const v = up.headers.get(h);
          if (v) respHeaders[h] = v;
        }
        res.writeHead(up.status, respHeaders);
        if (up.body) {
          Readable.fromWeb(up.body).pipe(res);
        } else {
          res.end();
        }
        return;
      }
      if (p === "/geetest") {
        const token = JSON.stringify(u.searchParams.get("token") || "");
        const gt = JSON.stringify(u.searchParams.get("gt") || "");
        const challenge = JSON.stringify(u.searchParams.get("challenge") || "");
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        return res.end(`<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <title>Bilibili 安全验证</title>
  <style>body{margin:0;padding:24px;background:#12141a;color:#fff;font-family:sans-serif;text-align:center;}#box{min-height:48px;display:flex;justify-content:center;}</style>
  <script src="https://static.geetest.com/static/js/gt.0.4.9.js"></script>
</head>
<body>
  <h3>请完成官方安全验证</h3>
  <div id="box">正在加载极验组件...</div>
  <script>
    const token=${token}, gt=${gt}, challenge=${challenge};
    if (typeof initGeetest === 'function') {
      initGeetest({ gt, challenge, offline: false, new_captcha: true, product: 'embed', width: '300px' }, function(obj) {
        document.getElementById('box').innerHTML = '';
        obj.appendTo('#box');
        obj.onSuccess(function() {
          const r = obj.getValidate();
          if (window.opener) {
            window.opener.postMessage({ type: 'geetest-verified', payload: {
              token, challenge: r.geetest_challenge, validate: r.geetest_validate, seccode: r.geetest_seccode
            }}, '*');
          }
          window.close();
        });
      });
    } else {
      document.getElementById('box').textContent = '极验组件加载失败，建议关闭改用扫码登录';
    }
  </script>
</body>
</html>`);
      }

      serveStatic(res, p);
    } catch (err) {
      sendJson(res, { error: String(err) }, 500);
    }
  });
}

function launchDesktopWindow(url) {
  const edgePaths = [
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
  ];
  const edge = edgePaths.find((p) => fs.existsSync(p));
  if (edge) {
    spawn(edge, [`--app=${url}`, "--window-size=1240,800"], {
      detached: true,
      stdio: "ignore",
    }).unref();
  } else {
    spawn("cmd", ["/c", "start", "", url], {
      detached: true,
      stdio: "ignore",
    }).unref();
  }
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const srv = createPureBiliServer();
  const preferredPort = Number(process.env.PUREBILI_PORT || 0);
  srv.listen(preferredPort, "127.0.0.1", () => {
    const addr = srv.address();
    serverPort = typeof addr === "object" && addr ? addr.port : 14200;
    const url = `http://127.0.0.1:${serverPort}`;
    console.log(`PureBili Windows server running at ${url}`);
    if (!process.argv.includes("--no-open")) {
      launchDesktopWindow(url);
    }
  });
}
