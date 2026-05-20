package com.hitif.videodownloader.network

import android.util.Log
import android.webkit.JavascriptInterface
import androidx.lifecycle.MutableLiveData
import com.hitif.videodownloader.download.WebViewFetchHelper
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType
import com.hitif.videodownloader.model.MediaQuality

/**
 * Native object exposed to JavaScript as window.HITIFAndroid.
 * Receives media URLs and response bodies discovered by the injected JS script.
 *
 * Supports:
 *   - 2-arg call: onMedia(url, type)          — generic media
 *   - 3-arg call: onMedia(url, type, title)   — YouTube formats with title
 *   - onXhrResponse(url, body, status)        — response body analysis
 *   - removeStaleYoutubeUrls()                — cleanup expired YouTube URLs
 */
class JsInterface(
    private val detector: MediaDetector,
    private val pageUrl: MutableLiveData<String>,
    private val pageTitle: MutableLiveData<String>
) {
    companion object { const val TAG = "JsInterface" }

    /**
     * Receives a media URL from JavaScript.
     *
     * @param url   The media URL
     * @param type  Media type hint (e.g. "video", "youtube_[720p]", "xhr", etc.)
     * @param title Optional title (for YouTube format extraction)
     */
    @JavascriptInterface
    fun onMedia(url: String, type: String, title: String?) {
        if (url.isBlank() || url.length < 10) return

        val effectiveTitle = title ?: pageTitle.value ?: ""

        // YouTube format: type starts with "youtube_" and contains quality info
        if (type.startsWith("youtube_")) {
            val detail = type.removePrefix("youtube_")
            val isVideoOnly = detail.contains("[video-only]")
            val isAudioOnly = detail.contains("[audio-only]")
            val isCombo = detail.contains("[combo]")

            // Extract quality label from brackets
            val qualityMatch = Regex("\\[(.+?)\\]\\s*(.+)").find(detail)
            val qualityLabel = qualityMatch?.groupValues?.get(1) ?: ""
            val resolution = qualityMatch?.groupValues?.get(2)?.trim() ?: detail.trim()

            val mediaType = when {
                isAudioOnly -> MediaType.AUDIO
                else -> MediaType.VIDEO
            }

            val quality = when {
                resolution.contains("2160") || resolution.contains("4K") -> MediaQuality.ULTRA
                resolution.contains("1440") || resolution.contains("2K") -> MediaQuality.HIGH
                resolution.contains("1080") -> MediaQuality.HIGH
                resolution.contains("720") -> MediaQuality.MEDIUM
                resolution.contains("480") -> MediaQuality.MEDIUM
                resolution.contains("360") -> MediaQuality.LOW
                resolution.contains("240") || resolution.contains("144") -> MediaQuality.LOW
                resolution.contains("kbps") -> MediaQuality.UNKNOWN
                else -> MediaQuality.UNKNOWN
            }

            // Build a descriptive filename from title + quality
            val cleanTitle = effectiveTitle
                .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
                .replace(Regex("\\s+"), "_")
                .replace(Regex("-{2,}"), "-")
                .trim('_', '-', ' ')
                .take(80)

            val ext = when {
                isAudioOnly -> "m4a"
                else -> "mp4"
            }
            val qualityTag = if (resolution.isNotBlank()) "_$resolution" else ""
            val filename = if (cleanTitle.length >= 3) {
                "${cleanTitle}${qualityTag}.$ext"
            } else {
                "youtube_${System.currentTimeMillis() / 1000}.$ext"
            }

            val item = MediaItem(
                url = url,
                filename = filename,
                mimeType = if (isAudioOnly) "audio/mp4" else "video/mp4",
                mediaType = mediaType,
                quality = quality,
                sizeBytes = -1L,
                pageUrl = pageUrl.value ?: "",
                pageTitle = effectiveTitle
            )

            Log.d(TAG, "YouTube format: $detail title=$effectiveTitle")
            detector.emitDirect(item)
            return
        }

        // Generic media (non-YouTube)
        detector.analyseUrl(
            url = url,
            pageUrl = pageUrl.value ?: "",
            pageTitle = pageTitle.value ?: ""
        )
    }

    /** Receives XHR/fetch response body for media content analysis. */
    @JavascriptInterface
    fun onXhrResponse(url: String, body: String, status: Int) {
        if (url.isBlank() || body.isBlank() || body.length < 10) return
        try {
            detector.analyseResponse(
                url = url,
                content = body,
                pageUrl = pageUrl.value ?: "",
                pageTitle = pageTitle.value ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "onXhrResponse error: ${e.message}")
        }
    }

    /**
     * Called from JS to remove stale YouTube (googlevideo.com) URLs from the
     * media panel. These URLs have short-lived signatures and expire quickly.
     * This is invoked by the 60s refresh timer in BrowserActivity before
     * re-parsing formats.
     */
    @JavascriptInterface
    fun removeStaleYoutubeUrls() {
        detector.removeStaleYoutubeUrls()
    }
}
