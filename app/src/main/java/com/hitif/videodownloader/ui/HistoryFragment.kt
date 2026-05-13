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
import com.hitif.videodownloader.databinding.FragmentHistoryBinding
import com.hitif.videodownloader.db.DownloadRecord
import com.hitif.videodownloader.download.DownloadHelper
import com.hitif.videodownloader.download.DownloadNotificationManager
import com.hitif.videodownloader.download.StorageMonitor
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType

class HistoryFragment : BottomSheetDialogFragment() {

    private var _b: FragmentHistoryBinding? = null
    private val b get() = _b!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: HistoryAdapter

    // Storage polling
    private val storageHandler = Handler(Looper.getMainLooper())
    private val storageRunnable = object : Runnable {
        override fun run() {
            updateStorageIndicator()
            storageHandler.postDelayed(this, 5_000L) // refresh every 5 s
        }
    }

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentHistoryBinding.inflate(inf, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.behavior?.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        setupList()
        setupButtons()
        observeHistory()
        // Start storage polling immediately
        storageHandler.post(storageRunnable)
    }

    // ── Storage indicator ──────────────────────────────────────────────────

    private fun updateStorageIndicator() {
        val _b = _b ?: return
        val free  = StorageMonitor.freeBytes()
        val total = StorageMonitor.totalBytes()
        val freeLabel  = StorageMonitor.formatBytes(free)
        val totalLabel = StorageMonitor.formatBytes(total)
        val pct        = StorageMonitor.freePercent()

        val storageText = "💾 $freeLabel libre / $totalLabel  ($pct%)"
        _b.tvCount.text = storageText

        // Colour the text according to how full the disk is
        val colorRes = when {
            pct <= 10 -> R.color.status_error     // critical — red
            pct <= 25 -> R.color.status_warning   // low — amber
            else      -> R.color.text_dim         // healthy — dim
        }
        _b.tvCount.setTextColor(
            androidx.core.content.ContextCompat.getColor(requireContext(), colorRes)
        )
    }

    // ── List setup ─────────────────────────────────────────────────────────

    private fun setupList() {
        adapter = HistoryAdapter(
            onDelete = { record ->
                try { DownloadNotificationManager.dismiss(record.url) } catch (_: Exception) {}
                vm.deleteHistoryRecord(record)
                Toast.makeText(requireContext(), "Supprimé de l'historique", Toast.LENGTH_SHORT).show()
            },
            onRetry  = { record ->
                val item = record.toMediaItem()
                DownloadHelper.enqueue(requireContext(), item)
                Toast.makeText(requireContext(),
                    "⬇ Relancé : ${record.filename.take(32)}", Toast.LENGTH_SHORT).show()
            },
            onShare  = { record ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, record.url)
                    putExtra(Intent.EXTRA_SUBJECT, record.filename)
                }
                startActivity(Intent.createChooser(intent, "Partager le lien"))
            }
        )
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter
        b.recyclerView.itemAnimator = null
    }

    private fun setupButtons() {
        b.btnClose.setOnClickListener { dismiss() }
        b.btnClearAll.setOnClickListener {
            try { DownloadNotificationManager.dismissAll() } catch (_: Exception) {}
            vm.clearHistory()
            Toast.makeText(requireContext(), "Historique effacé", Toast.LENGTH_SHORT).show()
        }
        b.btnClearFailed.setOnClickListener {
            vm.clearFailedHistory()
            Toast.makeText(requireContext(), "Échecs supprimés", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeHistory() {
        vm.downloadHistory.observe(viewLifecycleOwner) { records ->
            val items = records.toHistoryItems()
            adapter.submitList(items)
            val empty = records.isEmpty()
            b.emptyState.visibility   = if (empty) View.VISIBLE else View.GONE
            b.recyclerView.visibility = if (empty) View.GONE    else View.VISIBLE
            // Note: tvCount is now managed by the storage indicator runnable
        }
    }

    override fun onDestroyView() {
        storageHandler.removeCallbacks(storageRunnable)
        super.onDestroyView()
        _b = null
    }

    override fun getTheme() = R.style.Theme_BottomSheet_Holo
}

private fun DownloadRecord.toMediaItem() = MediaItem(
    url       = url,
    filename  = filename,
    mimeType  = mimeType,
    mediaType = mediaTypeEnum,
    pageTitle = pageTitle,
    pageUrl   = pageUrl,
    sizeBytes = sizeBytes
)
