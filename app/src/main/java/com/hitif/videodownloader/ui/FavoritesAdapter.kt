package com.hitif.videodownloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hitif.videodownloader.databinding.ItemFavoriteBinding
import com.hitif.videodownloader.db.FavoriteSite

class FavoritesAdapter(
    private val onOpen:   (FavoriteSite) -> Unit,
    private val onDelete: (FavoriteSite) -> Unit
) : ListAdapter<FavoriteSite, FavoritesAdapter.VH>(FavoriteDiff) {

    inner class VH(val binding: ItemFavoriteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val fav = getItem(position)
        with(holder.binding) {
            tvTitle.text = fav.title.ifBlank { fav.url }
            tvUrl.text = fav.url
            root.setOnClickListener { onOpen(fav) }
            btnOpen.setOnClickListener { onOpen(fav) }
            btnDelete.setOnClickListener { onDelete(fav) }
        }
    }
}

object FavoriteDiff : DiffUtil.ItemCallback<FavoriteSite>() {
    override fun areItemsTheSame(a: FavoriteSite, b: FavoriteSite) = a.id == b.id
    override fun areContentsTheSame(a: FavoriteSite, b: FavoriteSite) = a == b
}
