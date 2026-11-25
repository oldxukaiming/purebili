package com.noad.bilibili

import java.io.Serializable

data class UserProfile(
    val isLoggedIn: Boolean,
    val uid: Long,
    val uname: String,
    val face: String
)

data class QrCodeInfo(
    val url: String,
    val qrcodeKey: String
)

data class QrPollResult(
    val status: Int,
    val message: String,
    val loggedIn: Boolean
)

data class CaptchaInfo(
    val token: String,
    val gt: String,
    val challenge: String
)

data class PasswordLoginResult(
    val success: Boolean,
    val message: String,
    val verifyUrl: String?
)

data class FollowingUp(
    val mid: Long,
    val uname: String,
    val face: String
)

data class FollowingPage(
    val list: List<FollowingUp>,
    val total: Long,
    val hasMore: Boolean
)

data class FollowingTag(
    val tagid: Long,
    val name: String,
    val count: Long
)

data class VideoItem(
    val bvid: String,
    val aid: Long,
    val title: String,
    val cover: String,
    val durationText: String,
    val authorName: String,
    val authorMid: Long,
    val pubText: String,
    val invalid: Boolean
) : Serializable

data class FeedPage(
    val items: List<VideoItem>,
    val nextOffset: String,
    val hasMore: Boolean
)

data class FavFolder(
    val id: Long,
    val title: String,
    val mediaCount: Long,
    val isCollected: Boolean = false,
    val favState: Int = 0
)

data class VideoPart(
    val cid: Long,
    val page: Int,
    val part: String,
    val duration: Long
)

data class VideoDetail(
    val bvid: String,
    val aid: Long,
    val title: String,
    val desc: String,
    val authorName: String,
    val authorMid: Long,
    val pages: List<VideoPart>
)

data class QualityOption(
    val qn: Int,
    val desc: String
)

data class PlayStreams(
    val currentQn: Int,
    val qualities: List<QualityOption>,
    val dashVideoUrl: String?,
    val dashAudioUrl: String?,
    val mp4Url: String?
)
