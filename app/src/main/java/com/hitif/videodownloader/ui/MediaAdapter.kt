package com.hitif.videodownloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hitif.videodownloader.databinding.ItemMediaBinding
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType

class MediaAdapter(
    private val onDownload: (MediaItem) -> Unit,
    private val onDelete:   (MediaItem) -> Unit,
    private val onShare:    (MediaItem) -> Unit
) : ListAdapter<MediaItem, MediaAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MediaItem) {
            b.tvFilename.text  = item.filename.take(48)
            b.tvSize.text      = item.sizeLabel
            b.tvDuration.text  = item.durationLabel
            b.tvExt.text       = item.extensionLabel

            // Type badge colour
            val badgeColor = when (item.mediaType) {
                MediaType.HLS   -> 0xFF00E5FF.toInt()
                MediaType.DASH  -> 0xFFAA44FF.toInt()
                MediaType.AUDIO -> 0xFFFF44AA.toInt()
                else            -> 0xFF00C8FF.toInt()
            }
            b.tvExt.setTextColor(badgeColor)

            // Quality tag
            b.tvQuality.text = item.quality.name.take(1)
                .let { if (it == "U") "4K" else it }

            b.btnDownload.setOnClickListener { onDownload(item) }
            b.btnDelete.setOnClickListener   { onDelete(item) }
            b.btnShare.setOnClickListener    { onShare(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.id == b.id
            override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
        }
    }
}
