package com.noad.bilibili

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.noad.bilibili.databinding.ItemChipBinding
import com.noad.bilibili.databinding.ItemVideoCardBinding

data class ChipEntry(
    val id: Long,
    val label: String
)

class ChipAdapter(
    private val onSelect: (ChipEntry) -> Unit
) : RecyclerView.Adapter<ChipAdapter.ChipVH>() {

    private var items: List<ChipEntry> = emptyList()
    private var selectedId: Long = -1L

    fun submit(list: List<ChipEntry>, activeId: Long) {
        items = list
        selectedId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipVH {
        val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChipVH(binding)
    }

    override fun onBindViewHolder(holder: ChipVH, position: Int) {
        val entry = items[position]
        holder.binding.tvChip.text = entry.label
        holder.binding.tvChip.isSelected = (entry.id == selectedId)
        holder.binding.tvChip.setOnClickListener {
            selectedId = entry.id
            notifyDataSetChanged()
            onSelect(entry)
        }
    }

    override fun getItemCount(): Int = items.size

    class ChipVH(val binding: ItemChipBinding) : RecyclerView.ViewHolder(binding.root)
}

class VideoAdapter(
    private val onClick: (VideoItem, Int) -> Unit,
    private val onUnfavorite: ((VideoItem, Int) -> Unit)? = null
) : RecyclerView.Adapter<VideoAdapter.VideoVH>() {

    private val items = mutableListOf<VideoItem>()
    private var showUnfavoriteButton: Boolean = false

    fun setShowUnfavorite(show: Boolean) {
        if (showUnfavoriteButton != show) {
            showUnfavoriteButton = show
            notifyDataSetChanged()
        }
    }

    fun removeVideoAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun setVideos(list: List<VideoItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun appendVideos(list: List<VideoItem>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun currentItems(): List<VideoItem> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoVH {
        val binding = ItemVideoCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoVH(binding)
    }

    override fun onBindViewHolder(holder: VideoVH, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.tvTitle.text = item.title
        b.tvAuthor.text = if (item.pubText.isNotEmpty()) {
            "${item.authorName} · ${item.pubText}"
        } else {
            item.authorName
        }
        b.tvDuration.text = item.durationText.ifEmpty { "视频" }

        if (item.cover.isNotEmpty()) {
            Glide.with(b.imgCover)
                .load(BiliApi.glideUrl(item.cover))
                .into(b.imgCover)
        } else {
            b.imgCover.setImageDrawable(null)
        }

        b.root.alpha = if (item.invalid) 0.45f else 1.0f
        b.root.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.04f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        b.root.setOnClickListener {
            if (!item.invalid) {
                onClick(item, holder.bindingAdapterPosition)
            }
        }
        b.btnCardUnfav.visibility =
            if (showUnfavoriteButton && item.aid > 0L) View.VISIBLE else View.GONE
        b.btnCardUnfav.setOnClickListener {
            onUnfavorite?.invoke(item, holder.bindingAdapterPosition)
        }
        b.root.setOnLongClickListener {
            if (showUnfavoriteButton && item.aid > 0L) {
                onUnfavorite?.invoke(item, holder.bindingAdapterPosition)
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VideoVH(val binding: ItemVideoCardBinding) : RecyclerView.ViewHolder(binding.root)
}
