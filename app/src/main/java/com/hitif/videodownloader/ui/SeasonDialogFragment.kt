package com.hitif.videodownloader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        epAdapter = SeasonAdapter(
            episodes = episodes,
            onRenameEpisode = { position, currentFilename ->
                showEpisodeRenameDialog(position, currentFilename)
            }
        )
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
            val customFilenames = epAdapter.getCustomFilenames()
            DownloadHelper.enqueueBatch(requireContext(), items, customFilenames)
            Toast.makeText(
                requireContext(),
                "\u2b07 ${selected.size} épisode(s) en téléchargement",
                Toast.LENGTH_SHORT
            ).show()
            dismiss()
        }
    }

    /**
     * Shows an inline rename dialog for a specific episode in the season list.
     * Pre-fills the EditText with the current auto-generated (or custom) base name.
     */
    private fun showEpisodeRenameDialog(position: Int, currentFilename: String) {
        val episodes = vm.seasonGroups.value?.get(seriesKey) ?: return
        if (position !in episodes.indices) return

        val ep = episodes[position]
        val naming = SmartNaming.build(ep.item)

        // Determine the current base name (without extension)
        val currentBase = currentFilename.substringBeforeLast('.')

        // Create an EditText for the dialog
        val input = EditText(requireContext()).apply {
            setText(currentBase)
            setSelectAllOnFocus(true)
            setSingleLine(true)
            setTextSize(14f)
            setPaddingRelative(48, 32, 48, 32)
            setHint("Nom du fichier")
            setTextColor(0xFFEAFAFF.toInt())
            setHintTextColor(0xFF4A6878.toInt())
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Renommer ${ep.label}")
            .setView(input)
            .setPositiveButton("Confirmer") { _, _ ->
                val newBase = input.text.toString().trim()
                if (newBase.isNotBlank()) {
                    epAdapter.setCustomName(position, newBase)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
    override fun getTheme() = R.style.Theme_BottomSheet_Holo
}
