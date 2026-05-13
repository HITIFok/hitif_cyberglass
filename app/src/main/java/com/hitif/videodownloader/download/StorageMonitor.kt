package com.hitif.videodownloader.download

import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File

/**
 * Monitors available storage space on the device.
 *
 * Used before and during downloads to:
 *  - Warn if there is not enough space for the file
 *  - Abort the download if the disk fills up mid-stream
 */
object StorageMonitor {

    private const val TAG = "StorageMonitor"

    /**
     * Minimum free space required beyond the file size (safety buffer).
     * 50 MB keeps room for the OS and other apps.
     */
    private const val SAFETY_BUFFER_BYTES = 50L * 1024 * 1024   // 50 MB

    /**
     * Minimum absolute free space even if file size is unknown.
     * We refuse to start a download if less than this is available.
     */
    private const val MIN_FREE_BYTES = 100L * 1024 * 1024        // 100 MB

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Returns the number of free bytes on the primary external storage. */
    fun freeBytes(): Long {
        return try {
            val path = downloadsDir()
            val stat = StatFs(path.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.w(TAG, "freeBytes() failed: ${e.message}")
            Long.MAX_VALUE   // fail-open: if we can't check, don't block
        }
    }

    /** Returns total bytes on the primary external storage. */
    fun totalBytes(): Long {
        return try {
            val path = downloadsDir()
            val stat = StatFs(path.absolutePath)
            stat.blockCountLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.w(TAG, "totalBytes() failed: ${e.message}")
            Long.MAX_VALUE
        }
    }

    /** Returns free space as a percentage (0–100). */
    fun freePercent(): Int {
        val total = totalBytes()
        if (total <= 0L) return 100
        return ((freeBytes() * 100L) / total).toInt().coerceIn(0, 100)
    }

    /**
     * Returns [SpaceResult.OK] if there is enough space for [neededBytes].
     * If [neededBytes] ≤ 0 (unknown), we check against [MIN_FREE_BYTES].
     */
    fun checkSpace(neededBytes: Long): SpaceResult {
        val free = freeBytes()

        if (neededBytes > 0L) {
            val required = neededBytes + SAFETY_BUFFER_BYTES
            if (free < required) {
                return SpaceResult.InsufficientSpace(
                    freeBytes  = free,
                    neededBytes = required
                )
            }
        } else {
            // Unknown file size: just verify the minimum floor
            if (free < MIN_FREE_BYTES) {
                return SpaceResult.InsufficientSpace(
                    freeBytes  = free,
                    neededBytes = MIN_FREE_BYTES
                )
            }
        }
        return SpaceResult.OK
    }

    /**
     * Returns true if there is critically low storage (< [MIN_FREE_BYTES]).
     * Can be polled during an active download to abort early.
     */
    fun isCriticallyLow(): Boolean = freeBytes() < MIN_FREE_BYTES

    // -----------------------------------------------------------------------
    // Formatting helpers
    // -----------------------------------------------------------------------

    /** Human-readable byte count: "1.4 Go", "543 Mo", "12 Ko". */
    fun formatBytes(bytes: Long): String = when {
        bytes <= 0              -> "—"
        bytes < 1_024           -> "$bytes o"
        bytes < 1_024 * 1_024   -> String.format("%.1f Ko", bytes / 1_024.0)
        bytes < 1_024L * 1_024 * 1_024 ->
            String.format("%.1f Mo", bytes / (1_024.0 * 1_024.0))
        else ->
            String.format("%.2f Go", bytes / (1_024.0 * 1_024.0 * 1_024.0))
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private fun downloadsDir(): File {
        // Use the external Downloads directory as our reference path
        val ext = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (ext != null && ext.exists()) return ext
        // Fallback: root of external storage
        val root = Environment.getExternalStorageDirectory()
        if (root != null && root.exists()) return root
        // Last resort: internal cache
        return Environment.getDataDirectory()
    }

    // -----------------------------------------------------------------------
    // Result type
    // -----------------------------------------------------------------------

    sealed class SpaceResult {
        object OK : SpaceResult()

        data class InsufficientSpace(
            val freeBytes: Long,
            val neededBytes: Long
        ) : SpaceResult() {
            val message: String
                get() = "Espace insuffisant : ${formatBytes(freeBytes)} libre, " +
                        "${formatBytes(neededBytes)} nécessaires."
        }
    }
}
