package com.hitif.videodownloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hitif.videodownloader.databinding.ItemSeasonEpisodeBinding
import com.hitif.videodownloader.network.SmartNaming

class SeasonAdapter(
    private val episodes: List<SmartNaming.EpisodeCandidate>,
    private val onRenameEpisode: ((Int, String) -> Unit)? = null
) : RecyclerView.Adapter<SeasonAdapter.VH>() {

    // Track checked state
    private val checked = BooleanArray(episodes.size) { true }

    // Store custom filenames (URL -> custom base name)
    private val customNames = mutableMapOf<String, String>()

    inner class VH(val b: ItemSeasonEpisodeBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSeasonEpisodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = with(holder.b) {
        val ep = episodes[position]
        tvLabel.text    = ep.label                                   // S01E03

        // Show custom name if set, otherwise auto-generated
        val customBase = customNames[ep.item.url]
        val displayName = if (customBase != null) {
            val ext = SmartNaming.resolveExtension(ep.item)
            "$customBase.$ext"
        } else {
            SmartNaming.build(ep.item).filename
        }
        tvFilename.text = displayName

        // Highlight renamed items with a different color
        if (customBase != null) {
            tvFilename.setTextColor(0xFF00F5FF.toInt())  // accent_cyan
        } else {
            tvFilename.setTextColor(0xFFEAFAFF.toInt())  // text_bright
        }

        tvSize.text     = ep.item.sizeLabel
        checkBox.isChecked = checked[position]
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            checked[position] = isChecked
        }
        root.setOnClickListener { checkBox.toggle() }

        // Rename button
        btnRename.setOnClickListener {
            onRenameEpisode?.invoke(position, displayName)
        }
    }

    override fun getItemCount() = episodes.size

    /** Returns only the episodes the user has ticked */
    fun getSelectedEpisodes(): List<SmartNaming.EpisodeCandidate> =
        episodes.filterIndexed { i, _ -> checked[i] }

    /** Returns the map of URL -> custom base name for renamed episodes */
    fun getCustomFilenames(): Map<String, String> = customNames.toMap()

    /** Sets a custom base name for an episode at the given position */
    fun setCustomName(position: Int, baseName: String) {
        if (position in episodes.indices) {
            customNames[episodes[position].item.url] = baseName
            notifyItemChanged(position)
        }
    }

    fun selectAll()   { checked.fill(true);  notifyDataSetChanged() }
    fun deselectAll() { checked.fill(false); notifyDataSetChanged() }
}
