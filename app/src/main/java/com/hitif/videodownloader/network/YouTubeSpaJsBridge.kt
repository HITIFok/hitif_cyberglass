package com.hitif.videodownloader.network

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * JS Bridge for detecting YouTube SPA (Single Page Application) navigations.
 *
 * PROBLEM:
 * YouTube is a SPA — when the user clicks on another video, the URL changes
 * via history.pushState() WITHOUT triggering onPageFinished(). So
 * detectYouTubePage() only runs on the first page load.
 *
 * SOLUTION: This bridge intercepts:
 *   - history.pushState()   — main SPA navigation
 *   - history.replaceState() — URL replacement
 *   - popstate event         — back/forward buttons
 *   - <title> mutation       — title update after video loads
 *
 * Usage: Registered as "HITIFBridge" JavascriptInterface on the WebView.
 */
class YouTubeSpaJsBridge(
    private val onUrlChanged: (url: String, title: String) -> Unit
) {
    companion object {
        private const val TAG = "YouTubeSpaJsBridge"

        @Volatile
        private var lastReportedUrl = ""
        @Volatile
        private var lastReportedTime = 0L
        private const val DEBOUNCE_MS = 500L // Avoid rapid duplicates
    }

    /**
     * Called by JavaScript when history.pushState or replaceState fires.
     * Filters non-YouTube URLs and debounces rapid duplicates.
     */
    @JavascriptInterface
    fun onUrlChanged(url: String, title: String) {
        if (!isYouTubeVideoUrl(url)) return

        val now = System.currentTimeMillis()
        if (url == lastReportedUrl && now - lastReportedTime < DEBOUNCE_MS) return

        lastReportedUrl = url
        lastReportedTime = now

        Log.d(TAG, "SPA navigation detected: $url (title=$title)")
        onUrlChanged(url, title)
    }

    /**
     * Called when the page <title> changes (indicates video is ready).
     * Allows updating the MediaItem title after SPA navigation.
     */
    @JavascriptInterface
    fun onTitleChanged(url: String, title: String) {
        if (!isYouTubeVideoUrl(url)) return
        Log.d(TAG, "YouTube title updated: '$title' for $url")
    }

    private fun isYouTubeVideoUrl(url: String): Boolean =
        (url.contains("youtube.com/watch") ||
         url.contains("youtube.com/shorts/") ||
         url.contains("youtu.be/")) &&
        !url.contains("youtube.com/results") // Ignore search pages
}
