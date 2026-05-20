package com.hitif.videodownloader.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Legacy BroadcastReceiver for Android DownloadManager completions.
 * NO LONGER USED — all downloads now go through HITIF pipeline.
 * Kept as empty stub to avoid breaking manifest references.
 */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op: all downloads are handled by HlsDownloader / TurboDownloadEngine
    }
}
