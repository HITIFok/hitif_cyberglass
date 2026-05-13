package com.hitif.videodownloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hitif.videodownloader.databinding.ItemSeasonEpisodeBinding
import com.hitif.videodownloader.network.SmartNaming

class SeasonAdapter(
    private val episodes: List<SmartNaming.EpisodeCandidate>
) : RecyclerView.Adapter<SeasonAdapter.VH>() {

    // Track checked state
    private val checked = BooleanArray(episodes.size) { true }

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
        tvFilename.text = SmartNaming.build(ep.item).filename
        tvSize.text     = ep.item.sizeLabel
        checkBox.isChecked = checked[position]
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            checked[position] = isChecked
        }
        root.setOnClickListener { checkBox.toggle() }
    }

    override fun getItemCount() = episodes.size

    /** Returns only the episodes the user has ticked */
    fun getSelectedEpisodes(): List<SmartNaming.EpisodeCandidate> =
        episodes.filterIndexed { i, _ -> checked[i] }

    fun selectAll()   { checked.fill(true);  notifyDataSetChanged() }
    fun deselectAll() { checked.fill(false); notifyDataSetChanged() }
}
