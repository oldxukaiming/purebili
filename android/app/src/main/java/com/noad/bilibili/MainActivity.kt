package com.noad.bilibili

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.noad.bilibili.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chipAdapter: ChipAdapter
    private lateinit var videoAdapter: VideoAdapter

    private var activeTab: String = "following" // "following" | "favorites"
    private var followingList: MutableList<FollowingUp> = mutableListOf()
    private var followingTotal: Long = 0L
    private var followingUpPage: Int = 1
    private var followingUpHasMore: Boolean = false
    private var favFolderList: List<FavFolder> = emptyList()
    private var selectedUpMid: Long = -1L
    private var selectedFolderId: Long = -1L
    private var nextOffset: String = ""
    private var pageNumber: Int = 1
    private var hasMore: Boolean = false
    private var isLoading: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyDeviceModeLayout()
        setupLists()
        setupActions()
        checkAuthAndLoad()
    }

    private fun applyDeviceModeLayout() {
        val isTv = PureBiliApp.instance.isTvMode()
        binding.btnToggleMode.text = if (isTv) "TV模式" else "手机模式"
        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val isLandscape = isTv || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val spanCount = when {
            isTv -> 4
            isLandscape -> 3
            else -> 2
        }
        binding.rvVideos.layoutManager = GridLayoutManager(this, spanCount)
    }

    private fun setupLists() {
        chipAdapter = ChipAdapter { entry ->
            if (activeTab == "following") {
                if (entry.id == CHIP_LOAD_MORE_UPS) {
                    loadMoreUps()
                    return@ChipAdapter
                }
                selectedUpMid = entry.id
                binding.btnUnfollowUp.visibility = if (selectedUpMid > 0L) View.VISIBLE else View.GONE
                videoAdapter.setShowUnfavorite(false)
                loadFollowingVideos(reset = true)
            } else {
                selectedFolderId = entry.id
                binding.btnUnfollowUp.visibility = View.GONE
                val folder = favFolderList.firstOrNull { it.id == selectedFolderId }
                videoAdapter.setShowUnfavorite(folder != null && !folder.isCollected)
                loadFavVideos(reset = true)
            }
        }
        binding.rvChips.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvChips.adapter = chipAdapter

        videoAdapter = VideoAdapter(
            onClick = { _, position ->
                val list = ArrayList(videoAdapter.currentItems().filter { !it.invalid })
                val clicked = videoAdapter.currentItems().getOrNull(position) ?: return@VideoAdapter
                val validIdx = list.indexOfFirst { it.bvid == clicked.bvid }.coerceAtLeast(0)
                val folder = favFolderList.firstOrNull { it.id == selectedFolderId }
                val activeFavId =
                    if (activeTab == "favorites" && folder != null && !folder.isCollected) selectedFolderId else -1L
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("playlist", list)
                    putExtra("index", validIdx)
                    putExtra("fav_folder_id", activeFavId)
                }
                startActivity(intent)
            },
            onUnfavorite = { item, pos ->
                confirmUnfavoriteFromCard(item, pos)
            }
        )
        binding.rvVideos.adapter = videoAdapter
    }

    private fun setupActions() {
        binding.btnNavFollowing.isSelected = true
        binding.btnNavFollowing.setOnClickListener {
            if (activeTab != "following") {
                activeTab = "following"
                binding.btnNavFollowing.isSelected = true
                binding.btnNavFavorites.isSelected = false
                loadFollowingTab()
            }
        }
        binding.btnNavFavorites.setOnClickListener {
            if (activeTab != "favorites") {
                activeTab = "favorites"
                binding.btnNavFollowing.isSelected = false
                binding.btnNavFavorites.isSelected = true
                loadFavoritesTab()
            }
        }
        binding.btnToggleMode.setOnClickListener {
            val nowTv = PureBiliApp.instance.toggleUiMode()
            if (nowTv && !PureBiliApp.instance.dlnaRenderer.isEnabled()) {
                PureBiliApp.instance.dlnaRenderer.setEnabled(true)
            }
            updateDlnaReceiverButton()
            applyDeviceModeLayout()
        }
        updateDlnaReceiverButton()
        binding.btnDlnaReceiver.setOnClickListener {
            val renderer = PureBiliApp.instance.dlnaRenderer
            val next = !renderer.isEnabled()
            renderer.setEnabled(next)
            updateDlnaReceiverButton()
            val msg = if (next) {
                "已开启投屏接收，手机投屏可搜「${renderer.friendlyName}」"
            } else {
                "已关闭投屏接收"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        binding.btnRefresh.setOnClickListener {
            if (activeTab == "following") {
                loadFollowingVideos(reset = true)
            } else {
                loadFavVideos(reset = true)
            }
        }
        binding.btnLogout.setOnClickListener {
            lifecycleScope.launch {
                PureBiliApp.instance.api.logout()
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
            }
        }
        binding.btnLoadMore.setOnClickListener {
            if (activeTab == "following") {
                loadFollowingVideos(reset = false)
            } else {
                loadFavVideos(reset = false)
            }
        }
        binding.btnBrowseAllUps.setOnClickListener {
            openUpGroupOrSearchDialog()
        }
        binding.btnUnfollowUp.setOnClickListener {
            val mid = selectedUpMid
            if (mid <= 0L) return@setOnClickListener
            val upName = followingList.firstOrNull { it.mid == mid }?.uname ?: "该 UP 主"
            AlertDialog.Builder(this)
                .setTitle("确认取消关注")
                .setMessage("确定要取消关注「$upName」吗？")
                .setPositiveButton("确认取关") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            PureBiliApp.instance.api.unfollowUp(mid)
                            Toast.makeText(this@MainActivity, "已取消关注 $upName", Toast.LENGTH_SHORT).show()
                            loadFollowingTab()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "取消关注失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun checkAuthAndLoad() {
        showStatus("正在校验登录状态...")
        lifecycleScope.launch {
            try {
                val profile = PureBiliApp.instance.api.getProfile()
                if (!profile.isLoggedIn) {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                    return@launch
                }
                hideStatus()
                loadFollowingTab()
            } catch (e: Exception) {
                showStatus("连接 B 站失败：${e.message}")
            }
        }
    }

    private fun confirmUnfavoriteFromCard(item: VideoItem, position: Int) {
        val folderId = selectedFolderId
        if (folderId <= 0L || item.aid <= 0L) return
        AlertDialog.Builder(this)
            .setTitle("取消收藏")
            .setMessage("确定将「${item.title}」移出当前收藏夹吗？")
            .setPositiveButton("确认移除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        PureBiliApp.instance.api.unfavoriteVideo(item.aid, listOf(folderId))
                        videoAdapter.removeVideoAt(position)
                        Toast.makeText(this@MainActivity, "已取消收藏", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "取消收藏失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshUpChips() {
        val chips = mutableListOf(ChipEntry(-1L, "全部关注 (${followingTotal})"))
        followingList.forEach { chips.add(ChipEntry(it.mid, it.uname)) }
        if (followingUpHasMore) {
            chips.add(
                ChipEntry(
                    CHIP_LOAD_MORE_UPS,
                    "+ 更多UP (${followingList.size}/$followingTotal)"
                )
            )
        }
        chipAdapter.submit(chips, selectedUpMid)
    }

    private fun loadFollowingTab() {
        binding.btnUnfollowUp.visibility = View.GONE
        binding.btnBrowseAllUps.visibility = View.VISIBLE
        videoAdapter.setShowUnfavorite(false)
        lifecycleScope.launch {
            try {
                val res = PureBiliApp.instance.api.getFollowings(pn = 1, ps = 50)
                followingList.clear()
                followingList.addAll(res.list)
                followingTotal = res.total
                followingUpPage = 1
                followingUpHasMore = res.hasMore
                binding.btnNavFollowing.text = "关注 UP 主 ($followingTotal)"
                selectedUpMid = -1L
                refreshUpChips()
                loadFollowingVideos(reset = true)
            } catch (e: Exception) {
                showStatus("加载关注列表失败：${e.message}")
            }
        }
    }

    private fun loadMoreUps() {
        if (!followingUpHasMore) return
        lifecycleScope.launch {
            try {
                val nextPn = followingUpPage + 1
                val res = PureBiliApp.instance.api.getFollowings(pn = nextPn, ps = 50)
                val seen = followingList.map { it.mid }.toHashSet()
                res.list.filterTo(followingList) { seen.add(it.mid) }
                followingTotal = res.total
                followingUpPage = nextPn
                followingUpHasMore = res.hasMore
                refreshUpChips()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "加载更多 UP 失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openUpGroupOrSearchDialog() {
        lifecycleScope.launch {
            val tags = runCatching { PureBiliApp.instance.api.getFollowingTags() }.getOrDefault(emptyList())
            val labels = mutableListOf("按昵称搜索全部关注 (共 $followingTotal)")
            tags.forEach { labels.add("分组：${it.name} (${it.count})") }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("全部关注 UP 主（共 $followingTotal 位）")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == 0) {
                        promptSearchUpDialog()
                    } else {
                        val tag = tags[which - 1]
                        showTagUpsDialog(tag)
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun promptSearchUpDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "输入 UP 主昵称关键词"
            setTextColor(resources.getColor(R.color.text_primary))
            setHintTextColor(resources.getColor(R.color.text_secondary))
        }
        AlertDialog.Builder(this)
            .setTitle("搜索关注 UP 主")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                val kw = input.text.toString().trim()
                if (kw.isNotEmpty()) {
                    lifecycleScope.launch {
                        val res = PureBiliApp.instance.api.searchFollowings(kw, pn = 1, ps = 50)
                        showPickedUpDialog("搜索「$kw」结果 (${res.total})", res.list)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTagUpsDialog(tag: FollowingTag) {
        lifecycleScope.launch {
            val res = PureBiliApp.instance.api.getTagFollowings(tag.tagid, pn = 1, ps = 50)
            showPickedUpDialog("${tag.name} (${tag.count})", res.list)
        }
    }

    private fun showPickedUpDialog(title: String, list: List<FollowingUp>) {
        if (list.isEmpty()) {
            Toast.makeText(this, "该条件下暂无 UP 主", Toast.LENGTH_SHORT).show()
            return
        }
        val names = list.map { it.uname }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, which ->
                val picked = list[which]
                if (followingList.none { it.mid == picked.mid }) {
                    followingList.add(0, picked)
                }
                selectedUpMid = picked.mid
                binding.btnUnfollowUp.visibility = View.VISIBLE
                refreshUpChips()
                loadFollowingVideos(reset = true)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    companion object {
        private const val CHIP_LOAD_MORE_UPS = -999L
    }

    private fun loadFollowingVideos(reset: Boolean) {
        if (isLoading) return
        isLoading = true
        if (reset) {
            nextOffset = ""
            pageNumber = 1
            showStatus("正在加载视频...")
        }
        lifecycleScope.launch {
            try {
                val page = if (selectedUpMid <= 0L) {
                    PureBiliApp.instance.api.getFollowingFeed(nextOffset)
                } else {
                    PureBiliApp.instance.api.getUpVideos(selectedUpMid, pageNumber)
                }
                nextOffset = page.nextOffset
                pageNumber += 1
                hasMore = page.hasMore
                if (reset) {
                    videoAdapter.setVideos(page.items)
                } else {
                    videoAdapter.appendVideos(page.items)
                }
                binding.btnLoadMore.visibility = if (hasMore) View.VISIBLE else View.GONE
                hideStatus()
            } catch (e: Exception) {
                showStatus("加载失败：${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    private fun loadFavoritesTab() {
        binding.btnUnfollowUp.visibility = View.GONE
        binding.btnBrowseAllUps.visibility = View.GONE
        showStatus("正在加载收藏夹...")
        lifecycleScope.launch {
            try {
                val folders = PureBiliApp.instance.api.getFavFolders()
                favFolderList = folders
                if (folders.isEmpty()) {
                    chipAdapter.submit(emptyList(), -1L)
                    videoAdapter.setVideos(emptyList())
                    videoAdapter.setShowUnfavorite(false)
                    binding.btnLoadMore.visibility = View.GONE
                    showStatus("暂无收藏夹")
                    return@launch
                }
                val chips = folders.map { ChipEntry(it.id, "${it.title} (${it.mediaCount})") }
                selectedFolderId = folders.first().id
                videoAdapter.setShowUnfavorite(!folders.first().isCollected)
                chipAdapter.submit(chips, selectedFolderId)
                loadFavVideos(reset = true)
            } catch (e: Exception) {
                showStatus("加载收藏夹失败：${e.message}")
            }
        }
    }

    private fun loadFavVideos(reset: Boolean) {
        if (isLoading || selectedFolderId <= 0L) return
        isLoading = true
        if (reset) {
            pageNumber = 1
            showStatus("正在加载收藏视频...")
        }
        lifecycleScope.launch {
            try {
                val page = PureBiliApp.instance.api.getFavVideos(selectedFolderId, pageNumber)
                pageNumber += 1
                hasMore = page.hasMore
                if (reset) {
                    videoAdapter.setVideos(page.items)
                } else {
                    videoAdapter.appendVideos(page.items)
                }
                binding.btnLoadMore.visibility = if (hasMore) View.VISIBLE else View.GONE
                hideStatus()
            } catch (e: Exception) {
                showStatus("加载收藏视频失败：${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    private fun updateDlnaReceiverButton() {
        val enabled = PureBiliApp.instance.dlnaRenderer.isEnabled()
        binding.btnDlnaReceiver.text = if (enabled) "投屏接收:开" else "投屏接收:关"
        binding.btnDlnaReceiver.isSelected = enabled
    }

    private fun showStatus(msg: String) {
        binding.tvStatusBanner.text = msg
        binding.tvStatusBanner.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        binding.tvStatusBanner.visibility = View.GONE
    }
}
