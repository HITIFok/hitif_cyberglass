package com.hitif.videodownloader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

enum class MediaType { VIDEO, AUDIO, HLS, DASH, UNKNOWN }
enum class MediaQuality { UNKNOWN, LOW, MEDIUM, HIGH, ULTRA }

@Parcelize
data class MediaItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val filename: String,
    val mimeType: String = "",
    val mediaType: MediaType = MediaType.UNKNOWN,
    val quality: MediaQuality = MediaQuality.UNKNOWN,
    val sizeBytes: Long = -1L,
    val durationMs: Long = -1L,
    val pageUrl: String = "",
    val pageTitle: String = "",
    val thumbnailUrl: String = "",
    val discoveredAt: Long = System.currentTimeMillis(),
    var downloadId: Long = -1L,
    var downloadState: DownloadState = DownloadState.IDLE
) : Parcelable {

    val sizeLabel: String get() = when {
        sizeBytes <= 0 -> "—"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
        else -> String.format("%.1f MB", sizeBytes / (1024f * 1024f))
    }

    val durationLabel: String get() = when {
        durationMs <= 0 -> ""
        else -> {
            val s = (durationMs / 1000) % 60
            val m = (durationMs / 60000) % 60
            val h = durationMs / 3600000
            if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%d:%02d", m, s)
        }
    }

    val extensionLabel: String get() = when (mediaType) {
        MediaType.HLS  -> "MP4"
        MediaType.DASH -> "MP4"
        else -> filename.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "?" }
    }
}

enum class DownloadState { IDLE, QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }
