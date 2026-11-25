package com.noad.bilibili

import android.content.Context
import android.util.Base64
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

class BiliApi(context: Context) {

    private val prefs = context.getSharedPreferences("purebili_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cookies = LinkedHashMap<String, String>()

    private var cachedUid: Long = 0L
    private var wbiImgKey: String = ""
    private var wbiSubKey: String = ""
    private var wbiUpdatedAt: Long = 0L

    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        loadSession()
    }

    private fun loadSession() {
        val rawCookies = prefs.getString("cookies_json", null)
        if (!rawCookies.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val map: Map<String, String> = gson.fromJson(rawCookies, type)
                synchronized(cookies) {
                    cookies.clear()
                    cookies.putAll(map)
                }
            } catch (_: Exception) {
            }
        }
        cachedUid = prefs.getLong("uid", 0L)
        wbiImgKey = prefs.getString("wbi_img", "") ?: ""
        wbiSubKey = prefs.getString("wbi_sub", "") ?: ""
        wbiUpdatedAt = prefs.getLong("wbi_ts", 0L)
    }

    private fun saveSession() {
        val json = synchronized(cookies) { gson.toJson(cookies) }
        prefs.edit()
            .putString("cookies_json", json)
            .putLong("uid", cachedUid)
            .putString("wbi_img", wbiImgKey)
            .putString("wbi_sub", wbiSubKey)
            .putLong("wbi_ts", wbiUpdatedAt)
            .apply()
    }

    fun getCookieHeader(): String {
        synchronized(cookies) {
            return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    private fun storeResponseCookies(response: Response) {
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isEmpty()) return
        var changed = false
        synchronized(cookies) {
            for (header in setCookies) {
                val kv = header.substringBefore(';').trim()
                val idx = kv.indexOf('=')
                if (idx > 0) {
                    val k = kv.substring(0, idx).trim()
                    val v = kv.substring(idx + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) {
                        cookies[k] = v
                        changed = true
                    }
                }
            }
        }
        if (changed) saveSession()
    }

    private fun executeGetJson(url: String): JsonObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                val cookie = getCookieHeader()
                if (cookie.isNotEmpty()) header("Cookie", cookie)
            }
            .build()
        http.newCall(req).execute().use { resp ->
            storeResponseCookies(resp)
            val body = resp.body?.string() ?: "{}"
            return try {
                gson.fromJson(body, JsonObject::class.java) ?: JsonObject()
            } catch (_: Exception) {
                throw IllegalStateException("B站接口返回非 JSON (HTTP ${resp.code})")
            }
        }
    }

    private fun ensureBuvid() {
        val hasBuvid = synchronized(cookies) {
            cookies.containsKey("buvid3") && cookies.containsKey("buvid4") && cookies.containsKey("b_nut")
        }
        if (hasBuvid) return
        try {
            val homeReq = Request.Builder()
                .url("https://www.bilibili.com/")
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            http.newCall(homeReq).execute().use { resp ->
                storeResponseCookies(resp)
            }
        } catch (_: Exception) {
        }
        try {
            val res = executeGetJson("https://api.bilibili.com/x/frontend/finger/spi")
            val data = res.getAsJsonObject("data") ?: return
            synchronized(cookies) {
                data.get("b_3")?.asString?.let { cookies["buvid3"] = it }
                data.get("b_4")?.asString?.let { cookies["buvid4"] = it }
            }
            saveSession()
        } catch (_: Exception) {
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        synchronized(cookies) { cookies.clear() }
        cachedUid = 0L
        saveSession()
        ensureBuvid()
    }

    suspend fun getProfile(): UserProfile = withContext(Dispatchers.IO) {
        ensureBuvid()
        val res = executeGetJson("https://api.bilibili.com/x/web-interface/nav")
        val data = res.getAsJsonObject("data") ?: JsonObject()
        val isLogin = data.get("isLogin")?.asBoolean == true

        data.getAsJsonObject("wbi_img")?.let { wbi ->
            val img = extractWbiKey(wbi.get("img_url")?.asString ?: "")
            val sub = extractWbiKey(wbi.get("sub_url")?.asString ?: "")
            if (img.isNotEmpty() && sub.isNotEmpty()) {
                wbiImgKey = img
                wbiSubKey = sub
                wbiUpdatedAt = System.currentTimeMillis() / 1000
            }
        }

        if (isLogin) {
            cachedUid = data.get("mid")?.asLong ?: 0L
            val uname = data.get("uname")?.asString ?: "B站用户"
            val face = normalizeImgUrl(data.get("face")?.asString ?: "")
            saveSession()
            UserProfile(true, cachedUid, uname, face)
        } else {
            saveSession()
            UserProfile(false, 0L, "", "")
        }
    }

    private suspend fun getMixinKey(): String {
        val now = System.currentTimeMillis() / 1000
        if (wbiImgKey.isEmpty() || wbiSubKey.isEmpty() || now - wbiUpdatedAt > 3600) {
            getProfile()
        }
        val raw = wbiImgKey + wbiSubKey
        val sb = StringBuilder(32)
        for (i in 0 until 32) {
            val idx = MIXIN_KEY_ENC_TAB[i]
            if (idx < raw.length) sb.append(raw[idx])
        }
        return sb.toString()
    }

    private suspend fun signWbi(params: Map<String, String>): String {
        val mixinKey = getMixinKey()
        val sorted = TreeMap<String, String>(params)
        sorted["wts"] = (System.currentTimeMillis() / 1000).toString()

        val query = sorted.entries.joinToString("&") { (k, v) ->
            val clean = v.filter { it !in charArrayOf('!', '\'', '(', ')', '*') }
            "${encodeRfc3986(k)}=${encodeRfc3986(clean)}"
        }
        val md5Bytes = MessageDigest.getInstance("MD5").digest((query + mixinKey).toByteArray())
        val wRid = md5Bytes.joinToString("") { "%02x".format(it) }
        return "$query&w_rid=$wRid"
    }

    suspend fun generateQrCode(): QrCodeInfo = withContext(Dispatchers.IO) {
        ensureBuvid()
        val res = executeGetJson("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
        if (res.get("code")?.asInt != 0) {
            throw IllegalStateException(res.get("message")?.asString ?: "获取二维码失败")
        }
        val data = res.getAsJsonObject("data")
        QrCodeInfo(
            url = data.get("url").asString,
            qrcodeKey = data.get("qrcode_key").asString
        )
    }

    suspend fun pollQrCode(qrcodeKey: String): QrPollResult = withContext(Dispatchers.IO) {
        val res = executeGetJson(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=${encodeRfc3986(qrcodeKey)}"
        )
        val data = res.getAsJsonObject("data") ?: JsonObject()
        val code = data.get("code")?.asInt ?: -1
        val message = data.get("message")?.asString ?: ""
        if (code == 0) {
            getProfile()
            QrPollResult(0, "登录成功", true)
        } else {
            QrPollResult(code, message, false)
        }
    }

    suspend fun getPasswordCaptcha(): CaptchaInfo = withContext(Dispatchers.IO) {
        ensureBuvid()
        val res = executeGetJson("https://passport.bilibili.com/x/passport-login/captcha?source=main_web")
        if (res.get("code")?.asInt != 0) {
            throw IllegalStateException(res.get("message")?.asString ?: "获取验证码参数失败")
        }
        val data = res.getAsJsonObject("data")
        val geetest = data.getAsJsonObject("geetest")
        CaptchaInfo(
            token = data.get("token").asString,
            gt = geetest.get("gt").asString,
            challenge = geetest.get("challenge").asString
        )
    }

    suspend fun passwordLogin(
        username: String,
        password: String,
        token: String,
        challenge: String,
        validate: String,
        seccode: String
    ): PasswordLoginResult = withContext(Dispatchers.IO) {
        val keyRes = executeGetJson("https://passport.bilibili.com/x/passport-login/web/key")
        if (keyRes.get("code")?.asInt != 0) {
            throw IllegalStateException("获取加密公钥失败")
        }
        val keyData = keyRes.getAsJsonObject("data")
        val hash = keyData.get("hash").asString
        val pem = keyData.get("key").asString
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")

        val pubKeyBytes = Base64.decode(pem, Base64.DEFAULT)
        val pubKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pubKeyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encrypted = cipher.doFinal((hash + password).toByteArray(Charsets.UTF_8))
        val encodedPwd = Base64.encodeToString(encrypted, Base64.NO_WRAP)

        val form = FormBody.Builder()
            .add("username", username)
            .add("password", encodedPwd)
            .add("keep", "0")
            .add("token", token)
            .add("challenge", challenge)
            .add("validate", validate)
            .add("seccode", seccode)
            .add("source", "main_web")
            .add("go_url", "https://www.bilibili.com")
            .build()

        val req = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-login/web/login")
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .apply {
                val c = getCookieHeader()
                if (c.isNotEmpty()) header("Cookie", c)
            }
            .post(form)
            .build()

        http.newCall(req).execute().use { resp ->
            storeResponseCookies(resp)
            val json = gson.fromJson(resp.body?.string() ?: "{}", JsonObject::class.java)
            val code = json.get("code")?.asInt ?: -1
            if (code != 0) {
                return@withContext PasswordLoginResult(
                    false,
                    json.get("message")?.asString ?: "登录失败",
                    null
                )
            }
            val data = json.getAsJsonObject("data") ?: JsonObject()
            val status = data.get("status")?.asInt ?: 0
            if (status == 0) {
                getProfile()
                PasswordLoginResult(true, "登录成功", null)
            } else {
                PasswordLoginResult(
                    false,
                    data.get("message")?.asString ?: "需要二次验证，建议使用扫码登录",
                    data.get("url")?.asString
                )
            }
        }
    }

    suspend fun getFollowings(pn: Int = 1, ps: Int = 50): FollowingPage = withContext(Dispatchers.IO) {
        if (cachedUid == 0L) getProfile()
        val clampedPs = ps.coerceIn(1, 50)
        val res = executeGetJson(
            "https://api.bilibili.com/x/relation/followings?vmid=$cachedUid&pn=$pn&ps=$clampedPs&order=desc"
        )
        if (res.get("code")?.asInt != 0) {
            return@withContext FollowingPage(emptyList(), 0L, false)
        }
        val data = res.getAsJsonObject("data") ?: return@withContext FollowingPage(emptyList(), 0L, false)
        val list = data.getAsJsonArray("list")?.map { el ->
            val o = el.asJsonObject
            FollowingUp(
                mid = o.get("mid").asLong,
                uname = o.get("uname").asString,
                face = normalizeImgUrl(o.get("face")?.asString ?: "")
            )
        } ?: emptyList()
        val total = data.get("total")?.asLong ?: list.size.toLong()
        val hasMore = list.isNotEmpty() && ((pn - 1L) * clampedPs + list.size) < total
        FollowingPage(list, total, hasMore)
    }

    suspend fun searchFollowings(name: String, pn: Int = 1, ps: Int = 50): FollowingPage =
        withContext(Dispatchers.IO) {
            if (cachedUid == 0L) getProfile()
            val clampedPs = ps.coerceIn(1, 50)
            val res = executeGetJson(
                "https://api.bilibili.com/x/relation/followings/search?vmid=$cachedUid&name=${encodeRfc3986(name)}&pn=$pn&ps=$clampedPs"
            )
            if (res.get("code")?.asInt != 0) {
                return@withContext FollowingPage(emptyList(), 0L, false)
            }
            val data = res.getAsJsonObject("data") ?: return@withContext FollowingPage(emptyList(), 0L, false)
            val list = data.getAsJsonArray("list")?.map { el ->
                val o = el.asJsonObject
                FollowingUp(
                    mid = o.get("mid").asLong,
                    uname = o.get("uname").asString,
                    face = normalizeImgUrl(o.get("face")?.asString ?: "")
                )
            } ?: emptyList()
            val total = data.get("total")?.asLong ?: list.size.toLong()
            val hasMore = list.isNotEmpty() && ((pn - 1L) * clampedPs + list.size) < total
            FollowingPage(list, total, hasMore)
        }

    suspend fun getFollowingTags(): List<FollowingTag> = withContext(Dispatchers.IO) {
        val res = executeGetJson("https://api.bilibili.com/x/relation/tags")
        if (res.get("code")?.asInt != 0) return@withContext emptyList()
        res.getAsJsonArray("data")?.map { el ->
            val t = el.asJsonObject
            FollowingTag(
                tagid = t.get("tagid").asLong,
                name = t.get("name").asString,
                count = t.get("count")?.asLong ?: 0L
            )
        } ?: emptyList()
    }

    suspend fun getTagFollowings(tagid: Long, pn: Int = 1, ps: Int = 50): FollowingPage =
        withContext(Dispatchers.IO) {
            if (cachedUid == 0L) getProfile()
            val clampedPs = ps.coerceIn(1, 50)
            val res = executeGetJson(
                "https://api.bilibili.com/x/relation/tag?mid=$cachedUid&tagid=$tagid&pn=$pn&ps=$clampedPs"
            )
            if (res.get("code")?.asInt != 0) {
                return@withContext FollowingPage(emptyList(), 0L, false)
            }
            val list = res.getAsJsonArray("data")?.map { el ->
                val o = el.asJsonObject
                FollowingUp(
                    mid = o.get("mid").asLong,
                    uname = o.get("uname").asString,
                    face = normalizeImgUrl(o.get("face")?.asString ?: "")
                )
            } ?: emptyList()
            FollowingPage(list, list.size.toLong(), list.size >= clampedPs)
        }

    suspend fun unfollowUp(mid: Long) = withContext(Dispatchers.IO) {
        val csrf = synchronized(cookies) { cookies["bili_jct"] ?: "" }
        if (csrf.isEmpty()) {
            throw IllegalStateException("登录凭据缺少 CSRF，请重新登录")
        }
        val form = FormBody.Builder()
            .add("fid", mid.toString())
            .add("act", "2")
            .add("re_src", "11")
            .add("csrf", csrf)
            .build()
        val req = Request.Builder()
            .url("https://api.bilibili.com/x/relation/modify")
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
            .apply {
                val c = getCookieHeader()
                if (c.isNotEmpty()) header("Cookie", c)
            }
            .post(form)
            .build()
        http.newCall(req).execute().use { resp ->
            storeResponseCookies(resp)
            val body = resp.body?.string() ?: "{}"
            val json = gson.fromJson(body, JsonObject::class.java)
            if (json.get("code")?.asInt != 0) {
                throw IllegalStateException(json.get("message")?.asString ?: "取消关注失败")
            }
        }
    }

    suspend fun getFollowingFeed(offset: String): FeedPage = withContext(Dispatchers.IO) {
        val url = if (offset.isEmpty()) {
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=video"
        } else {
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=video&offset=${encodeRfc3986(offset)}"
        }
        val res = executeGetJson(url)
        if (res.get("code")?.asInt != 0) {
            throw IllegalStateException(res.get("message")?.asString ?: "获取关注动态失败")
        }
        val data = res.getAsJsonObject("data")
        val items = mutableListOf<VideoItem>()
        data.getAsJsonArray("items")?.forEach { el ->
            val item = el.asJsonObject
            val modules = item.getAsJsonObject("modules") ?: return@forEach
            val author = modules.getAsJsonObject("module_author") ?: JsonObject()
            val major = modules.getAsJsonObject("module_dynamic")?.getAsJsonObject("major") ?: return@forEach
            if (major.get("type")?.asString == "MAJOR_TYPE_ARCHIVE") {
                val arc = major.getAsJsonObject("archive") ?: return@forEach
                val bvid = arc.get("bvid")?.asString ?: return@forEach
                items.add(
                    VideoItem(
                        bvid = bvid,
                        aid = arc.get("aid")?.asString?.toLongOrNull() ?: 0L,
                        title = arc.get("title")?.asString ?: "",
                        cover = normalizeImgUrl(arc.get("cover")?.asString ?: ""),
                        durationText = arc.get("duration_text")?.asString ?: "",
                        authorName = author.get("name")?.asString ?: "",
                        authorMid = author.get("mid")?.asLong ?: 0L,
                        pubText = author.get("pub_time")?.asString ?: "",
                        invalid = false
                    )
                )
            }
        }
        FeedPage(
            items = items,
            nextOffset = data.get("offset")?.asString ?: "",
            hasMore = data.get("has_more")?.asBoolean == true
        )
    }

    suspend fun getUpVideos(mid: Long, pn: Int): FeedPage = withContext(Dispatchers.IO) {
        val query = signWbi(
            mapOf(
                "mid" to mid.toString(),
                "pn" to pn.toString(),
                "ps" to "24",
                "order" to "pubdate"
            )
        )
        val res = executeGetJson("https://api.bilibili.com/x/space/wbi/arc/search?$query")
        if (res.get("code")?.asInt != 0) {
            throw IllegalStateException(res.get("message")?.asString ?: "获取 UP 主视频失败")
        }
        val data = res.getAsJsonObject("data")
        val vlist = data.getAsJsonObject("list")?.getAsJsonArray("vlist")
        val count = data.getAsJsonObject("page")?.get("count")?.asLong ?: 0L
        val items = mutableListOf<VideoItem>()
        vlist?.forEach { el ->
            val v = el.asJsonObject
            items.add(
                VideoItem(
                    bvid = v.get("bvid")?.asString ?: "",
                    aid = v.get("aid")?.asLong ?: 0L,
                    title = v.get("title")?.asString ?: "",
                    cover = normalizeImgUrl(v.get("pic")?.asString ?: ""),
                    durationText = v.get("length")?.asString ?: "",
                    authorName = v.get("author")?.asString ?: "",
                    authorMid = v.get("mid")?.asLong ?: mid,
                    pubText = "",
                    invalid = false
                )
            )
        }
        FeedPage(items, (pn + 1).toString(), pn * 24L < count)
    }

    suspend fun getFavFolders(): List<FavFolder> = withContext(Dispatchers.IO) {
        if (cachedUid == 0L) getProfile()
        val out = mutableListOf<FavFolder>()
        val created = executeGetJson("https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$cachedUid")
        if (created.get("code")?.asInt == 0) {
            created.getAsJsonObject("data")?.getAsJsonArray("list")?.forEach { el ->
                val f = el.asJsonObject
                out.add(
                    FavFolder(
                        f.get("id").asLong,
                        f.get("title").asString,
                        f.get("media_count").asLong,
                        isCollected = false,
                        favState = f.get("fav_state")?.asInt ?: 0
                    )
                )
            }
        }
        val collected = executeGetJson("https://api.bilibili.com/x/v3/fav/folder/collected/list?up_mid=$cachedUid&pn=1&ps=50")
        if (collected.get("code")?.asInt == 0) {
            collected.getAsJsonObject("data")?.getAsJsonArray("list")?.forEach { el ->
                val f = el.asJsonObject
                out.add(
                    FavFolder(
                        f.get("id").asLong,
                        "${f.get("title").asString} (订阅)",
                        f.get("media_count").asLong,
                        isCollected = true,
                        favState = 0
                    )
                )
            }
        }
        out
    }

    suspend fun getVideoFavFolders(aid: Long): List<FavFolder> = withContext(Dispatchers.IO) {
        if (cachedUid == 0L) getProfile()
        val res = executeGetJson(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$cachedUid&rid=$aid&type=2"
        )
        if (res.get("code")?.asInt != 0) {
            throw IllegalStateException(res.get("message")?.asString ?: "查询收藏状态失败")
        }
        val out = mutableListOf<FavFolder>()
        res.getAsJsonObject("data")?.getAsJsonArray("list")?.forEach { el ->
            val f = el.asJsonObject
            out.add(
                FavFolder(
                    id = f.get("id").asLong,
                    title = f.get("title").asString,
                    mediaCount = f.get("media_count").asLong,
                    isCollected = false,
                    favState = f.get("fav_state")?.asInt ?: 0
                )
            )
        }
        out
    }

    suspend fun unfavoriteVideo(aid: Long, mediaIds: List<Long>) = withContext(Dispatchers.IO) {
        val csrf = synchronized(cookies) { cookies["bili_jct"] ?: "" }
        if (csrf.isEmpty()) {
            throw IllegalStateException("登录凭据缺少 CSRF，请重新登录")
        }
        if (aid <= 0L || mediaIds.isEmpty()) {
            throw IllegalStateException("缺少视频 aid 或收藏夹 ID")
        }
        val form = FormBody.Builder()
            .add("rid", aid.toString())
            .add("type", "2")
            .add("add_media_ids", "")
            .add("del_media_ids", mediaIds.joinToString(","))
            .add("csrf", csrf)
            .build()
        val req = Request.Builder()
            .url("https://api.bilibili.com/x/v3/fav/resource/deal")
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
            .apply {
                val c = getCookieHeader()
                if (c.isNotEmpty()) header("Cookie", c)
            }
            .post(form)
            .build()
        http.newCall(req).execute().use { resp ->
            storeResponseCookies(resp)
            val body = resp.body?.string() ?: "{}"
            val json = gson.fromJson(body, JsonObject::class.java)
            if (json.get("code")?.asInt != 0) {
                throw IllegalStateException(json.get("message")?.asString ?: "取消收藏失败")
            }
        }
    }

    suspend fun getFavVideos(mediaId: Long, pn: Int): FeedPage = withContext(Dispatchers.IO) {
        val res = executeGetJson(
            "https://api.bilibili.com/x/v3/fav/resource/list?media_id=$mediaId&pn=$pn&ps=20&platform=web"
        )
        if (res.get("code")?.asInt != 0) {
            throw IllegalStateException(res.get("message")?.asString ?: "获取收藏夹视频失败")
        }
        val data = res.getAsJsonObject("data")
        val items = mutableListOf<VideoItem>()
        data.getAsJsonArray("medias")?.forEach { el ->
            val m = el.asJsonObject
            if ((m.get("type")?.asInt ?: 2) != 2) return@forEach
            val attr = m.get("attr")?.asInt ?: 0
            val title = m.get("title")?.asString ?: ""
            val upper = m.getAsJsonObject("upper") ?: JsonObject()
            items.add(
                VideoItem(
                    bvid = m.get("bvid")?.asString ?: "",
                    aid = m.get("id")?.asLong ?: 0L,
                    title = title,
                    cover = normalizeImgUrl(m.get("cover")?.asString ?: ""),
                    durationText = formatDuration(m.get("duration")?.asLong ?: 0L),
                    authorName = upper.get("name")?.asString ?: "",
                    authorMid = upper.get("mid")?.asLong ?: 0L,
                    pubText = "",
                    invalid = attr == 9 || title == "已失效视频"
                )
            )
        }
        FeedPage(items, (pn + 1).toString(), data.get("has_more")?.asBoolean == true)
    }

    suspend fun getVideoDetail(bvid: String): VideoDetail = withContext(Dispatchers.IO) {
        ensureBuvid()
        val res = executeGetJson(
            "https://api.bilibili.com/x/web-interface/view?bvid=${encodeRfc3986(bvid)}"
        )
        if (res.get("code")?.asInt != 0) {
            throw IllegalStateException(res.get("message")?.asString ?: "获取视频信息失败")
        }
        val d = res.getAsJsonObject("data")
        val parts = mutableListOf<VideoPart>()
        d.getAsJsonArray("pages")?.forEach { el ->
            val p = el.asJsonObject
            parts.add(
                VideoPart(
                    cid = p.get("cid").asLong,
                    page = p.get("page").asInt,
                    part = p.get("part")?.asString ?: "正片",
                    duration = p.get("duration")?.asLong ?: 0L
                )
            )
        }
        VideoDetail(
            bvid = d.get("bvid")?.asString ?: bvid,
            aid = d.get("aid")?.asLong ?: 0L,
            title = d.get("title")?.asString ?: "",
            desc = d.get("desc")?.asString ?: "",
            authorName = d.getAsJsonObject("owner")?.get("name")?.asString ?: "",
            authorMid = d.getAsJsonObject("owner")?.get("mid")?.asLong ?: 0L,
            pages = parts
        )
    }

    suspend fun getPlayStreams(bvid: String, cid: Long, qn: Int): PlayStreams = withContext(Dispatchers.IO) {
        val dashQuery = signWbi(
            mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to qn.toString(),
                "fnval" to "4048",
                "fnver" to "0",
                "fourk" to "1"
            )
        )
        val dashRes = executeGetJson("https://api.bilibili.com/x/player/wbi/playurl?$dashQuery")
        if (dashRes.get("code")?.asInt != 0) {
            throw IllegalStateException(dashRes.get("message")?.asString ?: "获取播放地址失败")
        }
        val data = dashRes.getAsJsonObject("data")
        val currentQn = data.get("quality")?.asInt ?: qn

        val qualities = mutableListOf<QualityOption>()
        val aq = data.getAsJsonArray("accept_quality")
        val ad = data.getAsJsonArray("accept_description")
        if (aq != null && ad != null) {
            for (i in 0 until minOf(aq.size(), ad.size())) {
                qualities.add(QualityOption(aq[i].asInt, ad[i].asString))
            }
        }

        var dashVideoUrl: String? = null
        var dashAudioUrl: String? = null
        data.getAsJsonObject("dash")?.let { dash ->
            val videos = dash.getAsJsonArray("video")
            if (videos != null && videos.size() > 0) {
                // Prefer H.264 (avc1) for hardware compatibility on older 32-bit Xiaomi TVs
                val candidates = videos.map { it.asJsonObject }
                val avcMatched = candidates.firstOrNull {
                    it.get("id")?.asInt == qn && (it.get("codecs")?.asString ?: "").startsWith("avc")
                } ?: candidates.firstOrNull {
                    (it.get("codecs")?.asString ?: "").startsWith("avc")
                } ?: candidates.firstOrNull()
                dashVideoUrl = avcMatched?.let { pickBestStreamUrl(it, "baseUrl", "base_url") }
            }
            val audios = dash.getAsJsonArray("audio")
            if (audios != null && audios.size() > 0) {
                val bestAudio = audios.map { it.asJsonObject }
                    .maxByOrNull { it.get("bandwidth")?.asLong ?: 0L }
                dashAudioUrl = bestAudio?.let { pickBestStreamUrl(it, "baseUrl", "base_url") }
            }
        }

        // MP4 fallback (single-stream hardware decoding for very old TVs)
        var mp4Url: String? = null
        try {
            val mp4Query = signWbi(
                mapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                    "qn" to qn.toString(),
                    "fnval" to "1",
                    "fnver" to "0"
                )
            )
            val mp4Res = executeGetJson("https://api.bilibili.com/x/player/wbi/playurl?$mp4Query")
            val durl0 = mp4Res.getAsJsonObject("data")
                ?.getAsJsonArray("durl")
                ?.firstOrNull()
                ?.asJsonObject
            mp4Url = durl0?.let { pickBestStreamUrl(it, "url", "url") }
        } catch (_: Exception) {
        }

        PlayStreams(currentQn, qualities, dashVideoUrl, dashAudioUrl, mp4Url)
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val REFERER = "https://www.bilibili.com/"

        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
        )

        fun glideUrl(url: String): GlideUrl {
            val headers = LazyHeaders.Builder()
                .addHeader("Referer", REFERER)
                .addHeader("User-Agent", UA)
                .build()
            return GlideUrl(normalizeImgUrl(url), headers)
        }

        private fun pickBestStreamUrl(obj: JsonObject, key1: String, key2: String): String? {
            val candidates = mutableListOf<String>()
            obj.get(key1)?.asString?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            if (key2 != key1) {
                obj.get(key2)?.asString?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            }
            listOf("backupUrl", "backup_url").forEach { bk ->
                obj.getAsJsonArray(bk)?.forEach { el ->
                    el.asString?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
                }
            }
            return candidates.firstOrNull {
                it.contains("bilivideo.com") || it.contains("bilivideo.cn")
            } ?: candidates.firstOrNull()
        }

        private fun normalizeImgUrl(raw: String): String {
            return when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
                else -> raw
            }
        }

        private fun extractWbiKey(url: String): String {
            return url.substringAfterLast('/').substringBefore('.')
        }

        private fun encodeRfc3986(v: String): String {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        }

        private fun formatDuration(secs: Long): String {
            val h = secs / 3600
            val m = (secs % 3600) / 60
            val s = secs % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
