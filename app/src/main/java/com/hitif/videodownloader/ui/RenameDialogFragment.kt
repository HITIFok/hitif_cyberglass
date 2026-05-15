package com.hitif.videodownloader.ui

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hitif.videodownloader.databinding.DialogRenameBinding
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType
import com.hitif.videodownloader.network.SmartNaming

/**
 * Dialog that lets the user rename a file before downloading.
 *
 * The auto-generated SmartNaming filename is pre-filled in the EditText.
 * The extension is shown separately and is NOT editable (users should not
 * change .mp4 to .txt, etc.). The user can freely edit the base name.
 *
 * Usage:
 *   RenameDialogFragment.newInstance(item).show(fragmentManager, "rename")
 *
 * The result is delivered via [onFilenameConfirmed] listener.
 */
class RenameDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_ITEM_JSON = "item_json"

        fun newInstance(item: MediaItem): RenameDialogFragment {
            val f = RenameDialogFragment()
            f.arguments = Bundle().apply {
                putParcelable("media_item", item)
            }
            return f
        }
    }

    /** Called when the user confirms the filename. */
    var onFilenameConfirmed: ((baseName: String, extension: String) -> Unit)? = null

    private var _b: DialogRenameBinding? = null
    private val b get() = _b!!

    private lateinit var item: MediaItem
    private lateinit var extension: String
    private lateinit var autoBaseName: String

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        @Suppress("DEPRECATION")
        item = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            requireArguments().getParcelable("media_item", MediaItem::class.java)!!
        else
            requireArguments().getParcelable("media_item")!!

        // Compute auto-generated filename
        val naming = SmartNaming.build(item)
        extension = naming.extension
        autoBaseName = naming.baseName

        // Inflate custom view
        _b = DialogRenameBinding.inflate(LayoutInflater.from(requireContext()))

        // Set info text
        b.tvInfo.text = "Nom suggéré par SmartNaming"

        // Pre-fill the editable name (without extension)
        b.etFilename.setText(autoBaseName)

        // Show extension (non-editable)
        b.tvExtension.text = ".$extension"

        // Show type badge
        val typeLabel = when (item.mediaType) {
            MediaType.HLS   -> "HLS"
            MediaType.DASH  -> "DASH"
            MediaType.AUDIO -> "Audio"
            else            -> "Vidéo"
        }
        b.tvBadge.text = "$typeLabel \u2022 ${extension.uppercase()}"

        // Select all text so user can quickly replace it
        b.etFilename.selectAll()

        // Build dialog
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(b.root)
            .setCancelable(true)
            .create()

        // Buttons
        b.btnCancel.setOnClickListener { dismiss() }
        b.btnConfirm.setOnClickListener {
            val baseName = b.etFilename.text.toString().trim()
            if (baseName.isNotBlank()) {
                onFilenameConfirmed?.invoke(baseName, extension)
            }
            dismiss()
        }

        // Show keyboard automatically
        dialog.setOnShowListener {
            b.etFilename.requestFocus()
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }

        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
