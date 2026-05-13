package com.hitif.videodownloader.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hitif.videodownloader.R
import com.hitif.videodownloader.databinding.FragmentMediaPanelBinding
import com.hitif.videodownloader.download.DownloadHelper
import com.hitif.videodownloader.download.StorageMonitor
import com.hitif.videodownloader.model.MediaItem

class MediaPanelFragment : BottomSheetDialogFragment() {

    private var _b: FragmentMediaPanelBinding? = null
    private val b get() = _b!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: MediaAdapter

    // Storage polling (every 5 s)
    private val storageHandler = Handler(Looper.getMainLooper())
    private val storageRunnable = object : Runnable {
        override fun run() {
            updateStorageSubtitle()
            storageHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _b = FragmentMediaPanelBinding.inflate(inf, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.behavior?.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        setupRecyclerView()
        setupButtons()
        observeMedia()
        storageHandler.post(storageRunnable)
    }

    // ── Storage subtitle ───────────────────────────────────────────────────

    private fun updateStorageSubtitle() {
        val _b = _b ?: return
        val free  = StorageMonitor.freeBytes()
        val pct   = StorageMonitor.freePercent()
        val label = StorageMonitor.formatBytes(free)

        val colorRes = when {
            pct <= 10 -> R.color.status_error
            pct <= 25 -> R.color.status_warning
            else      -> R.color.text_dim
        }
        val mediaCount = vm.mediaItems.value?.size ?: 0
        val countPart  = if (mediaCount > 0) "$mediaCount détecté(s) · " else ""
        _b.tvMediaCount.text = "${countPart}💾 $label libre ($pct%)"
        _b.tvMediaCount.setTextColor(
            androidx.core.content.ContextCompat.getColor(requireContext(), colorRes)
        )
    }

    // ── RecyclerView ───────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = MediaAdapter(
            onDownload = ::download,
            onDelete   = { vm.removeItem(it) },
            onShare    = ::share
        )
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter
        b.recyclerView.itemAnimator = null
    }

    // ── Buttons ────────────────────────────────────────────────────────────

    private fun setupButtons() {
        b.btnClose.setOnClickListener { dismiss() }

        b.btnClearAll.setOnClickListener {
            vm.clearMedia()
            Toast.makeText(requireContext(), "Liste vidée", Toast.LENGTH_SHORT).show()
        }

        b.btnDownloadAll.setOnClickListener {
            val items = vm.mediaItems.value.orEmpty()
            if (items.isEmpty()) {
                Toast.makeText(requireContext(), "Aucun média détecté", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Check available space before launching all downloads
            val totalSize = items.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            val spaceCheck = StorageMonitor.checkSpace(totalSize)
            if (spaceCheck is StorageMonitor.SpaceResult.InsufficientSpace) {
                Toast.makeText(requireContext(), spaceCheck.message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            items.forEach { download(it) }
            Toast.makeText(requireContext(),
                "${items.size} téléchargement(s) lancé(s)", Toast.LENGTH_SHORT).show()
        }

        b.btnSeason.setOnClickListener {
            val groups = vm.seasonGroups.value ?: return@setOnClickListener
            val firstKey = groups.keys.firstOrNull() ?: return@setOnClickListener
            SeasonDialogFragment.newInstance(firstKey)
                .show(parentFragmentManager, "season_dialog")
        }
    }

    // ── Observers ──────────────────────────────────────────────────────────

    private fun observeMedia() {
        vm.mediaItems.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items.toList())
            val empty = items.isEmpty()
            b.emptyState.visibility   = if (empty) View.VISIBLE else View.GONE
            b.recyclerView.visibility = if (empty) View.GONE    else View.VISIBLE
            // update the subtitle with fresh storage info + media count
            updateStorageSubtitle()
        }

        vm.seasonGroups.observe(viewLifecycleOwner) { groups ->
            b.btnSeason.visibility = if (groups.isNotEmpty()) View.VISIBLE else View.GONE
            if (groups.isNotEmpty()) {
                val count = groups.values.sumOf { it.size }
                b.btnSeason.text = "📺 ${groups.size} SAISON(S) · $count ÉP."
            }
        }
    }

    // ── Download helpers ───────────────────────────────────────────────────

    private fun download(item: MediaItem) {
        try {
            // Quick storage check before each individual download
            val check = StorageMonitor.checkSpace(item.sizeBytes)
            if (check is StorageMonitor.SpaceResult.InsufficientSpace) {
                Toast.makeText(requireContext(), check.message, Toast.LENGTH_LONG).show()
                return
            }
            DownloadHelper.enqueue(requireContext(), item)
            Toast.makeText(requireContext(),
                "⬇ ${item.filename.take(32)}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun share(item: MediaItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, item.url)
            putExtra(Intent.EXTRA_SUBJECT, item.filename)
        }
        startActivity(Intent.createChooser(intent, "Partager le lien"))
    }

    override fun onDestroyView() {
        storageHandler.removeCallbacks(storageRunnable)
        super.onDestroyView()
        _b = null
    }

    override fun getTheme() = R.style.Theme_BottomSheet_Holo
}
