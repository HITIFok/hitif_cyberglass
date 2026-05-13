package com.hitif.videodownloader.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hitif.videodownloader.model.DownloadState
import com.hitif.videodownloader.model.MediaType

@Entity(tableName = "download_history")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Identifiers
    val downloadManagerId: Long = -1L,
    val url: String,

    // Smart-named filename (final, after SmartNaming applied)
    val filename: String,

    // Metadata
    val pageTitle: String = "",
    val pageUrl: String   = "",
    val mimeType: String  = "",
    val mediaType: String = MediaType.VIDEO.name,
    val sizeBytes: Long   = -1L,

    // Season info (null if standalone)
    val seriesName: String? = null,
    val season: Int?        = null,
    val episode: Int?       = null,

    // State
    val state: String = DownloadState.QUEUED.name,

    // Progress tracking
    val progressBytes: Long = 0L,
    val totalBytes: Long   = -1L,
    val speedBps: Long     = 0L,

    // Timestamps
    val startedAt: Long     = System.currentTimeMillis(),
    val completedAt: Long?  = null
) {
    val stateEnum: DownloadState
        get() = try { DownloadState.valueOf(state) } catch (_: Exception) { DownloadState.FAILED }

    val mediaTypeEnum: MediaType
        get() = try { MediaType.valueOf(mediaType) } catch (_: Exception) { MediaType.VIDEO }

    /** Human-readable size */
    val sizeLabel: String get() = when {
        sizeBytes <= 0L               -> "—"
        sizeBytes < 1_024 * 1_024     -> "${sizeBytes / 1_024} KB"
        else -> String.format("%.1f MB", sizeBytes / (1_024f * 1_024f))
    }

    /** e.g. "Game of Thrones S02E05" */
    val seriesLabel: String? get() = when {
        seriesName != null && season != null && episode != null ->
            "$seriesName S${season.toString().padStart(2,'0')}E${episode.toString().padStart(2,'0')}"
        seriesName != null -> seriesName
        else -> null
    }
}
