package com.noad.bilibili

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.noad.bilibili.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    private var playlist: List<VideoItem> = emptyList()
    private var currentVideoIdx: Int = 0
    private var detail: VideoDetail? = null
    private var currentPartIdx: Int = 0
    private var currentQn: Int = 80
    private var useMp4Mode: Boolean = false
    private var currentSpeed: Float = 1.0f
    private var latestStreams: PlayStreams? = null
    private var activeCastDevice: DlnaDevice? = null
    private var activeFavFolderId: Long = -1L
    private var isExternalCastMode: Boolean = false

    private val hideToastHandler = Handler(Looper.getMainLooper())
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            val exo = player
            if (isExternalCastMode && exo != null) {
                val renderer = PureBiliApp.instance.dlnaRenderer
                renderer.positionMs = exo.currentPosition.coerceAtLeast(0L)
                renderer.durationMs = exo.duration.coerceAtLeast(0L)
                renderer.transportState = when {
                    exo.playbackState == Player.STATE_ENDED -> "STOPPED"
                    exo.isPlaying -> "PLAYING"
                    else -> "PAUSED_PLAYBACK"
                }
            }
            syncHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupControls()
        registerRendererListener()
        handlePlayerIntent(intent)
        syncHandler.post(syncRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayerIntent(intent)
    }

    private fun handlePlayerIntent(intent: Intent) {
        val castUrl = intent.getStringExtra(EXTRA_CAST_URL)
        if (!castUrl.isNullOrEmpty()) {
            isExternalCastMode = true
            val title = intent.getStringExtra(EXTRA_CAST_TITLE) ?: "手机投屏视频"
            binding.btnQuality.visibility = View.GONE
            binding.btnStreamMode.visibility = View.GONE
            binding.btnParts.visibility = View.GONE
            binding.btnUnfollowAuthor.visibility = View.GONE
            binding.btnUnfavVideo.visibility = View.GONE
            binding.btnCast.visibility = View.GONE
            binding.tvPlayerTitle.text = "[投屏] $title"
            startDirectCastUrl(castUrl)
            return
        }

        isExternalCastMode = false
        binding.btnQuality.visibility = View.VISIBLE
        binding.btnStreamMode.visibility = View.VISIBLE
        binding.btnUnfollowAuthor.visibility = View.VISIBLE
        binding.btnUnfavVideo.visibility = View.VISIBLE
        binding.btnCast.visibility = View.VISIBLE

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        playlist = (intent.getSerializableExtra("playlist") as? ArrayList<VideoItem>) ?: emptyList()
        currentVideoIdx = intent.getIntExtra("index", 0)
        activeFavFolderId = intent.getLongExtra("fav_folder_id", -1L)
        loadCurrentVideo()
    }

    private fun registerRendererListener() {
        PureBiliApp.instance.dlnaRenderer.listener = object : DlnaRendererServer.PlaybackListener {
            override fun onCastPlayControl() {
                runOnUiThread {
                    player?.play()
                    showToast("投屏遥控：继续播放")
                }
            }

            override fun onCastPauseControl() {
                runOnUiThread {
                    player?.pause()
                    showToast("投屏遥控：已暂停")
                }
            }

            override fun onCastStopControl() {
                runOnUiThread {
                    if (isExternalCastMode) {
                        finish()
                    }
                }
            }

            override fun onCastSeekControl(positionMs: Long) {
                runOnUiThread {
                    player?.seekTo(positionMs)
                    showToast("同步进度")
                }
            }
        }
    }

    private fun startDirectCastUrl(url: String) {
        val exo = player ?: return
        val headers = mapOf(
            "Referer" to BiliApi.REFERER,
            "User-Agent" to BiliApi.UA,
            "Origin" to "https://www.bilibili.com"
        )
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val uri = Uri.parse(url)
        val mediaSource = if (url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
        }
        exo.setMediaSource(mediaSource)
        exo.playbackParameters = PlaybackParameters(currentSpeed)
        exo.prepare()
        exo.playWhenReady = true
        PureBiliApp.instance.dlnaRenderer.transportState = "PLAYING"
        showToast("已接收投屏并开始播放")
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        if (isExternalCastMode) {
                            PureBiliApp.instance.dlnaRenderer.transportState = "STOPPED"
                        } else {
                            playNextAuto()
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Fallback automatically to MP4 single-stream hardware decoding on older TVs
                    if (!useMp4Mode && latestStreams?.mp4Url != null) {
                        useMp4Mode = true
                        binding.btnStreamMode.text = "MP4兼容"
                        showToast("已自动切换为 MP4 硬件兼容流")
                        startPlaybackFromStreams()
                    } else {
                        Toast.makeText(
                            this@PlayerActivity,
                            "播放出错：${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
        }
    }

    private fun setupControls() {
        binding.btnPlayerBack.setOnClickListener { finish() }

        binding.btnStreamMode.setOnClickListener {
            useMp4Mode = !useMp4Mode
            binding.btnStreamMode.text = if (useMp4Mode) "MP4兼容" else "DASH"
            startPlaybackFromStreams()
        }

        binding.btnSpeed.setOnClickListener {
            val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
            val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            AlertDialog.Builder(this)
                .setTitle("选择倍速")
                .setItems(labels) { _, which ->
                    currentSpeed = values[which]
                    binding.btnSpeed.text = labels[which]
                    player?.playbackParameters = PlaybackParameters(currentSpeed)
                }
                .show()
        }

        binding.btnQuality.setOnClickListener {
            val qualities = latestStreams?.qualities.orEmpty()
            if (qualities.isEmpty()) return@setOnClickListener
            val labels = qualities.map { it.desc }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("选择画质")
                .setItems(labels) { _, which ->
                    currentQn = qualities[which].qn
                    loadStreamsForPart()
                }
                .show()
        }

        binding.btnParts.setOnClickListener {
            val parts = detail?.pages.orEmpty()
            if (parts.isEmpty()) return@setOnClickListener
            val labels = parts.map { "P${it.page} ${it.part}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("分 P 选集")
                .setItems(labels) { _, which ->
                    currentPartIdx = which
                    loadStreamsForPart()
                }
                .show()
        }

        binding.btnUnfollowAuthor.setOnClickListener {
            val mid = detail?.authorMid ?: playlist.getOrNull(currentVideoIdx)?.authorMid ?: 0L
            val name = detail?.authorName ?: playlist.getOrNull(currentVideoIdx)?.authorName ?: "该 UP 主"
            if (mid <= 0L) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("确认取消关注")
                .setMessage("确定要取消关注「$name」吗？")
                .setPositiveButton("确认取关") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            PureBiliApp.instance.api.unfollowUp(mid)
                            binding.btnUnfollowAuthor.text = "已取关"
                            binding.btnUnfollowAuthor.isEnabled = false
                            Toast.makeText(this@PlayerActivity, "已取消关注 $name", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@PlayerActivity, "取消关注失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnCast.setOnClickListener {
            if (activeCastDevice != null) {
                showCastRemoteDialog()
            } else {
                scanAndCast()
            }
        }

        binding.btnUnfavVideo.setOnClickListener {
            handleUnfavoriteInPlayer()
        }
    }

    private fun loadCurrentVideo() {
        val video = playlist.getOrNull(currentVideoIdx) ?: return
        binding.tvPlayerTitle.text = video.title
        currentPartIdx = 0
        lifecycleScope.launch {
            try {
                val d = PureBiliApp.instance.api.getVideoDetail(video.bvid)
                detail = d
                binding.tvPlayerTitle.text = d.title
                binding.btnParts.visibility = if (d.pages.size > 1) View.VISIBLE else View.GONE
                loadStreamsForPart()
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "加载详情失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadStreamsForPart() {
        val d = detail ?: return
        val part = d.pages.getOrNull(currentPartIdx) ?: return
        lifecycleScope.launch {
            try {
                val streams = PureBiliApp.instance.api.getPlayStreams(d.bvid, part.cid, currentQn)
                latestStreams = streams
                val qDesc = streams.qualities.firstOrNull { it.qn == streams.currentQn }?.desc ?: "画质"
                binding.btnQuality.text = qDesc
                binding.btnStreamMode.text = if (useMp4Mode) "MP4兼容" else "DASH"
                startPlaybackFromStreams()
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "获取播放流失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startPlaybackFromStreams() {
        val exo = player ?: return
        val streams = latestStreams ?: return

        val headers = mapOf(
            "Referer" to BiliApi.REFERER,
            "User-Agent" to BiliApi.UA,
            "Origin" to "https://www.bilibili.com"
        )
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val mediaSource = if (!useMp4Mode && streams.dashVideoUrl != null && streams.dashAudioUrl != null) {
            val vSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(streams.dashVideoUrl)))
            val aSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(streams.dashAudioUrl)))
            MergingMediaSource(vSource, aSource)
        } else if (streams.mp4Url != null) {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(streams.mp4Url)))
        } else {
            Toast.makeText(this, "未找到可用播放流", Toast.LENGTH_SHORT).show()
            return
        }

        exo.setMediaSource(mediaSource)
        exo.playbackParameters = PlaybackParameters(currentSpeed)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun playNextAuto() {
        val d = detail
        if (d != null && currentPartIdx + 1 < d.pages.size) {
            currentPartIdx += 1
            loadStreamsForPart()
        } else if (currentVideoIdx + 1 < playlist.size) {
            currentVideoIdx += 1
            loadCurrentVideo()
        }
    }

    // Remote control D-Pad support for Xiaomi TV
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val exo = player
            val focusedTopBar = binding.playerTopOverlay.hasFocus()
            if (!focusedTopBar && exo != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val target = (exo.currentPosition + 10_000L).coerceAtMost(exo.duration.coerceAtLeast(0L))
                        exo.seekTo(target)
                        showToast("快进 +10秒")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val target = (exo.currentPosition - 10_000L).coerceAtLeast(0L)
                        exo.seekTo(target)
                        showToast("快退 -10秒")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (exo.isPlaying) {
                            exo.pause()
                            showToast("已暂停")
                        } else {
                            exo.play()
                            showToast("继续播放")
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.btnPlayerBack.requestFocus()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleUnfavoriteInPlayer() {
        val aid = detail?.aid ?: playlist.getOrNull(currentVideoIdx)?.aid ?: 0L
        if (aid <= 0L) {
            Toast.makeText(this, "未获取到视频 ID，无法操作", Toast.LENGTH_SHORT).show()
            return
        }
        if (activeFavFolderId > 0L) {
            AlertDialog.Builder(this)
                .setTitle("取消收藏")
                .setMessage("确定将当前视频从收藏夹移出吗？")
                .setPositiveButton("确认取藏") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            PureBiliApp.instance.api.unfavoriteVideo(aid, listOf(activeFavFolderId))
                            binding.btnUnfavVideo.text = "已取藏"
                            binding.btnUnfavVideo.isEnabled = false
                            Toast.makeText(this@PlayerActivity, "已取消收藏", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@PlayerActivity, "取消收藏失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        lifecycleScope.launch {
            try {
                val folders = PureBiliApp.instance.api.getVideoFavFolders(aid)
                val faved = folders.filter { it.favState == 1 }
                if (faved.isEmpty()) {
                    Toast.makeText(this@PlayerActivity, "该视频未在自建收藏夹中", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val folderNames = faved.joinToString("、") { it.title }
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("取消收藏")
                    .setMessage("该视频已收藏在「$folderNames」，确定全部取消收藏吗？")
                    .setPositiveButton("确认取藏") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                PureBiliApp.instance.api.unfavoriteVideo(aid, faved.map { it.id })
                                binding.btnUnfavVideo.text = "已取藏"
                                binding.btnUnfavVideo.isEnabled = false
                                Toast.makeText(this@PlayerActivity, "已取消收藏", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@PlayerActivity, "取消收藏失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "查询收藏状态失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun scanAndCast() {
        val mp4Url = latestStreams?.mp4Url
        if (mp4Url.isNullOrEmpty()) {
            Toast.makeText(this, "正在等待视频流解析完成，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        showToast("正在搜索局域网 DLNA 电视...")
        lifecycleScope.launch {
            val devices = PureBiliApp.instance.dlnaCaster.discoverDevices()
            if (devices.isEmpty()) {
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("未发现电视设备")
                    .setMessage("请确认手机与电视连接在同一 Wi-Fi，且电视已打开投屏接收（DLNA）功能。")
                    .setPositiveButton("重新扫描") { _, _ -> scanAndCast() }
                    .setNegativeButton("关闭", null)
                    .show()
                return@launch
            }
            val labels = devices.map { "${it.name} (${it.host})" }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("选择投屏电视")
                .setItems(labels) { _, which ->
                    val chosen = devices[which]
                    val title = detail?.title ?: playlist.getOrNull(currentVideoIdx)?.title ?: "PureBili"
                    showToast("正在投屏到 ${chosen.name}...")
                    lifecycleScope.launch {
                        try {
                            PureBiliApp.instance.dlnaCaster.castVideo(chosen, mp4Url, title)
                            activeCastDevice = chosen
                            binding.btnCast.text = "投屏中"
                            player?.pause()
                            showCastRemoteDialog()
                        } catch (e: Exception) {
                            Toast.makeText(this@PlayerActivity, "投屏失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showCastRemoteDialog() {
        val dev = activeCastDevice ?: return
        val actions = arrayOf("继续播放", "暂停播放", "停止投屏", "重新选择电视")
        AlertDialog.Builder(this)
            .setTitle("正在投屏：${dev.name}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch { runCatching { PureBiliApp.instance.dlnaCaster.control(dev, "play") } }
                    1 -> lifecycleScope.launch { runCatching { PureBiliApp.instance.dlnaCaster.control(dev, "pause") } }
                    2 -> lifecycleScope.launch {
                        runCatching { PureBiliApp.instance.dlnaCaster.control(dev, "stop") }
                        activeCastDevice = null
                        binding.btnCast.text = "投屏"
                    }
                    3 -> scanAndCast()
                }
            }
            .setNegativeButton("收起", null)
            .show()
    }

    private fun showToast(text: String) {
        binding.tvSeekToast.text = text
        binding.tvSeekToast.visibility = View.VISIBLE
        hideToastHandler.removeCallbacksAndMessages(null)
        hideToastHandler.postDelayed({
            binding.tvSeekToast.visibility = View.GONE
        }, 1500)
    }

    override fun onDestroy() {
        syncHandler.removeCallbacksAndMessages(null)
        hideToastHandler.removeCallbacksAndMessages(null)
        PureBiliApp.instance.dlnaRenderer.listener = null
        if (isExternalCastMode) {
            PureBiliApp.instance.dlnaRenderer.transportState = "STOPPED"
        }
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CAST_URL = "cast_direct_url"
        const val EXTRA_CAST_TITLE = "cast_direct_title"
    }
}
