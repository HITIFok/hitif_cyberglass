package com.hitif.videodownloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hitif.videodownloader.databinding.ItemTabBinding

class TabAdapter(
    private val onSwitch:  (Tab) -> Unit,
    private val onClose:   (Tab) -> Unit
) : ListAdapter<Tab, TabAdapter.VH>(TabDiff) {

    var activeTabId: String? = null

    inner class VH(val binding: ItemTabBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tab = getItem(position)
        with(holder.binding) {
            tvTitle.text = tab.title
            tvUrl.text = tab.url
            val isActive = tab.id == activeTabId
            activeIndicator.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.GONE
            card.alpha = if (isActive) 1f else 0.7f

            root.setOnClickListener { onSwitch(tab) }
            btnCloseTab.setOnClickListener { onClose(tab) }
        }
    }
}

object TabDiff : DiffUtil.ItemCallback<Tab>() {
    override fun areItemsTheSame(a: Tab, b: Tab) = a.id == b.id
    override fun areContentsTheSame(a: Tab, b: Tab) = a.url == b.url && a.title == b.title
}
