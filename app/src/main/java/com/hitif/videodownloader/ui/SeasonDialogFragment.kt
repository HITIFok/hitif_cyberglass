package com.hitif.videodownloader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hitif.videodownloader.R
import com.hitif.videodownloader.databinding.FragmentSeasonBinding
import com.hitif.videodownloader.download.DownloadHelper
import com.hitif.videodownloader.network.SmartNaming

class SeasonDialogFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_SERIES_KEY = "series_key"

        fun newInstance(seriesKey: String): SeasonDialogFragment {
            val f = SeasonDialogFragment()
            f.arguments = Bundle().apply { putString(ARG_SERIES_KEY, seriesKey) }
            return f
        }
    }

    private var _b: FragmentSeasonBinding? = null
    private val b get() = _b!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var epAdapter: SeasonAdapter
    private lateinit var seriesKey: String

    override fun onCreateView(inf: LayoutInflater, p: ViewGroup?, s: Bundle?): View {
        _b = FragmentSeasonBinding.inflate(inf, p, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        seriesKey = arguments?.getString(ARG_SERIES_KEY) ?: return

        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED

        val groups   = vm.seasonGroups.value ?: return
        val episodes = groups[seriesKey] ?: return

        setupHeader(episodes)
        setupList(episodes)
        setupButtons(episodes)
    }

    private fun setupHeader(episodes: List<SmartNaming.EpisodeCandidate>) {
        val name = episodes.firstOrNull()?.seriesName ?: "Série"
        val season = episodes.firstOrNull()?.season
        b.tvSeriesName.text = name.uppercase()
        b.tvSeasonLabel.text = if (season != null)
            "SAISON ${season.toString().padStart(2,'0')} · ${episodes.size} ÉPISODES"
        else
            "${episodes.size} ÉPISODES"
    }

    private fun setupList(episodes: List<SmartNaming.EpisodeCandidate>) {
        epAdapter = SeasonAdapter(episodes)
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = epAdapter
    }

    private fun setupButtons(episodes: List<SmartNaming.EpisodeCandidate>) {
        b.btnClose.setOnClickListener { dismiss() }

        b.btnSelectAll.setOnClickListener   { epAdapter.selectAll() }
        b.btnDeselectAll.setOnClickListener { epAdapter.deselectAll() }

        b.btnDownloadSelected.setOnClickListener {
            val selected = epAdapter.getSelectedEpisodes()
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), "Aucun épisode sélectionné", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val items = selected.map { it.item }
            DownloadHelper.enqueueBatch(requireContext(), items)
            Toast.makeText(
                requireContext(),
                "⬇ ${selected.size} épisode(s) en téléchargement",
                Toast.LENGTH_SHORT
            ).show()
            dismiss()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
    override fun getTheme() = R.style.Theme_BottomSheet_Holo
}
