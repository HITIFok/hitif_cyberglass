package com.hitif.videodownloader.download

import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import com.hitif.videodownloader.db.AppDatabase
import com.hitif.videodownloader.db.DownloadRecord
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType
import com.hitif.videodownloader.network.SmartNaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URL

/**
 * Download orchestrator — ALL downloads go through our own pipeline
 * (HlsDownloader or TurboDownloadEngine), never through Android DownloadManager.
 * Progress is tracked via Room DB + DownloadNotificationManager.
 *
 * Concurrency: A semaphore limits simultaneous downloads to MAX_CONCURRENT.
 * Extra items are queued and start automatically as others finish.
 * This prevents connection saturation when downloading entire seasons.
 */
object DownloadHelper {

    private const val TAG = "DownloadHelper"
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Max simultaneous downloads. Each download may use up to 4 chunks,
     *  so 2 concurrent = 8 connections max (within OkHttp pool of 8). */
    private const val MAX_CONCURRENT = 2
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT)

    @Volatile
    private var initialized = false

    fun init(context: android.content.Context) {
        if (initialized) return
        initialized = true
        DownloadNotificationManager.init(context)
    }

    @Synchronized
    private fun ensureService(context: android.content.Context) {
        if (!initialized) init(context)
        try {
            DownloadProgressService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DownloadProgressService: ${e.message}")
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** Single download — starts immediately (bypasses semaphore). */
    fun enqueue(context: android.content.Context, item: MediaItem): Long {
        if (!initialized) init(context)
        val naming = SmartNaming.build(item)
        return startDownload(context, item, naming, insertDb = true)
    }

    /**
     * Batch download (season packs, "Download All") with concurrency control.
     * Only MAX_CONCURRENT downloads run at a time; the rest wait in a queue.
     * All items appear immediately in history as QUEUED, then transition to
     * DOWNLOADING as slots become available.
     */
    fun enqueueBatch(
        context: android.content.Context,
        items: List<MediaItem>
    ): List<Pair<MediaItem, Long>> {
        if (!initialized) init(context)
        val db = AppDatabase.getInstance(context)
        val results = mutableListOf<Pair<MediaItem, Long>>()

        for (item in items) {
            val naming = SmartNaming.build(item)
            val safeFilename = when (item.mediaType) {
                MediaType.HLS -> sanitizeFilename(naming.filename)
                else          -> naming.filename
            }

            // Insert immediately as QUEUED so user sees all episodes in history
            scope.launch {
                try {
                    db.downloadDao().insert(
                        DownloadRecord(
                            downloadManagerId = -1L,
                            url = item.url, filename = safeFilename,
                            pageTitle = item.pageTitle, pageUrl = item.pageUrl,
                            mimeType = item.mimeType, mediaType = item.mediaType.name,
                            sizeBytes = item.sizeBytes,
                            seriesName = naming.seriesName, season = naming.season,
                            episode = naming.episode,
                            state = "QUEUED", startedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert batch record: ${e.message}")
                }
                ensureService(context)
            }

            // Wait for a semaphore slot, then start the actual download
            scope.launch {
                downloadSemaphore.withPermit {
                    // Move from QUEUED → DOWNLOADING now that we have a slot
                    try {
                        db.downloadDao().updateStateByUrl(
                            url = item.url, state = "DOWNLOADING",
                            ts = System.currentTimeMillis()
                        )
                    } catch (_: Exception) {}

                    startDownload(context, item, naming, insertDb = false)
                }
            }

            results.add(item to -1L)
        }
        return results
    }

    fun cancel(url: String) {
        TurboDownloadEngine.cancel(url)
        HlsDownloader.cancel(url)
    }

    fun cancelAll() {
        TurboDownloadEngine.cancelAll()
        HlsDownloader.cancelAll()
    }

    // =========================================================================
    // Internal — unified download starter
    // =========================================================================

    /**
     * Start a download. If [insertDb] is true, a DOWNLOADING record is
     * inserted into the DB (used for single downloads from enqueue).
     * If false, the record was already inserted by enqueueBatch.
     */
    private fun startDownload(
        context: android.content.Context,
        item: MediaItem,
        naming: SmartNaming.NameResult,
        insertDb: Boolean
    ): Long {
        return when (item.mediaType) {
            MediaType.HLS -> downloadHls(context, item, naming, insertDb)
            else          -> downloadDirect(context, item, naming, insertDb)
        }
    }

    // ========================================================================
    // HLS download
    // ========================================================================

    private fun downloadHls(
        context: android.content.Context,
        item: MediaItem,
        naming: SmartNaming.NameResult,
        insertDb: Boolean
    ): Long {
        val subDir = buildSubDir(item)
        val safeFilename = sanitizeFilename(naming.filename)
        val headers = buildHeaders(item)
        val db = AppDatabase.getInstance(context)

        if (insertDb) {
            scope.launch {
                try {
                    db.downloadDao().insert(
                        DownloadRecord(
                            downloadManagerId = -1L,
                            url = item.url, filename = safeFilename,
                            pageTitle = item.pageTitle, pageUrl = item.pageUrl,
                            mimeType = "video/mp4", mediaType = MediaType.HLS.name,
                            sizeBytes = item.sizeBytes,
                            seriesName = naming.seriesName, season = naming.season,
                            episode = naming.episode,
                            state = "DOWNLOADING", startedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert HLS record: ${e.message}")
                }
                ensureService(context)
            }
        } else {
            scope.launch { ensureService(context) }
        }

        val throttler = DbThrottler(db, item.url, scope)

        HlsDownloader.download(
            context = context, m3u8Url = item.url,
            filename = safeFilename, subDir = subDir, headers = headers,
            callback = object : TurboCallback {
                override fun onProgress(progress: TurboProgress) {
                    try {
                        DownloadNotificationManager.showProgress(
                            url = item.url, title = safeFilename,
                            bytesDownloaded = progress.bytesDownloaded,
                            totalBytes = progress.totalBytes,
                            speedBps = progress.speedBps, percent = progress.percent
                        )
                    } catch (_: Exception) {}

                    if (progress.totalBytes > 0 && progress.bytesDownloaded == 0L) {
                        scope.launch {
                            try {
                                db.downloadDao().updateExpectedSizeByUrl(
                                    url = item.url, totalBytes = progress.totalBytes
                                )
                            } catch (_: Exception) {}
                        }
                    }
                    throttler.update(progress)
                }

                override fun onComplete(file: File) {
                    try {
                        scope.launch {
                            try {
                                db.downloadDao().completeDownloadByUrl(
                                    url = item.url, state = "COMPLETED",
                                    ts = System.currentTimeMillis(), fileSize = file.length()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to complete HLS record: ${e.message}")
                            }
                        }
                        DownloadNotificationManager.showComplete(item.url, safeFilename, file.length())
                    } catch (_: Exception) {}
                }

                override fun onError(error: Throwable) {
                    Log.e(TAG, "HLS error: ${error.message}", error)
                    try {
                        scope.launch {
                            try {
                                db.downloadDao().updateStateByUrl(
                                    url = item.url, state = "FAILED",
                                    ts = System.currentTimeMillis()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to update HLS error: ${e.message}")
                            }
                        }
                        DownloadNotificationManager.showError(
                            item.url, safeFilename, error.message ?: "Erreur inconnue"
                        )
                    } catch (_: Exception) {}
                }
            }
        )
        return -1L
    }

    // ========================================================================
    // Direct download (mp4, mkv, webm…) via TurboDownloadEngine
    // ========================================================================

    private fun downloadDirect(
        context: android.content.Context,
        item: MediaItem,
        naming: SmartNaming.NameResult,
        insertDb: Boolean
    ): Long {
        val subDir = buildSubDir(item, naming)
        val safeFilename = naming.filename
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val destPath = "${downloadsDir.absolutePath}/$subDir/$safeFilename"

        val headers = buildHeaders(item)
        val db = AppDatabase.getInstance(context)

        if (insertDb) {
            scope.launch {
                try {
                    db.downloadDao().insert(
                        DownloadRecord(
                            downloadManagerId = -1L,
                            url = item.url, filename = safeFilename,
                            pageTitle = item.pageTitle, pageUrl = item.pageUrl,
                            mimeType = item.mimeType, mediaType = item.mediaType.name,
                            sizeBytes = item.sizeBytes,
                            seriesName = naming.seriesName, season = naming.season,
                            episode = naming.episode,
                            state = "DOWNLOADING", startedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert direct record: ${e.message}")
                }
                ensureService(context)
            }
        } else {
            scope.launch { ensureService(context) }
        }

        val throttler = DbThrottler(db, item.url, scope)

        TurboDownloadEngine.download(
            context = context, url = item.url, destPath = destPath, headers = headers,
            callback = object : TurboCallback {
                override fun onProgress(progress: TurboProgress) {
                    try {
                        DownloadNotificationManager.showProgress(
                            url = item.url, title = safeFilename,
                            bytesDownloaded = progress.bytesDownloaded,
                            totalBytes = progress.totalBytes,
                            speedBps = progress.speedBps, percent = progress.percent
                        )
                    } catch (_: Exception) {}

                    if (progress.totalBytes > 0 && progress.bytesDownloaded == 0L) {
                        scope.launch {
                            try {
                                db.downloadDao().updateExpectedSizeByUrl(
                                    url = item.url, totalBytes = progress.totalBytes
                                )
                            } catch (_: Exception) {}
                        }
                    }

                    throttler.update(progress)
                }

                override fun onComplete(file: File) {
                    try {
                        scope.launch {
                            try {
                                db.downloadDao().completeDownloadByUrl(
                                    url = item.url, state = "COMPLETED",
                                    ts = System.currentTimeMillis(), fileSize = file.length()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to complete direct record: ${e.message}")
                            }
                        }
                        DownloadNotificationManager.showComplete(item.url, safeFilename, file.length())
                        DownloadNotificationManager.dismiss(item.url)

                        val ext = safeFilename.substringAfterLast('.', "mp4")
                        val mimeMap = mapOf(
                            "mp4"  to "video/mp4",
                            "mkv"  to "video/x-matroska",
                            "webm" to "video/webm",
                            "mp3"  to "audio/mpeg",
                            "m4a"  to "audio/mp4"
                        )
                        android.media.MediaScannerConnection.scanFile(
                            context, arrayOf(file.absolutePath),
                            arrayOf(mimeMap[ext] ?: "video/mp4"), null
                        )
                    } catch (_: Exception) {}
                }

                override fun onError(error: Throwable) {
                    Log.e(TAG, "Direct download error: ${error.message}", error)
                    try {
                        scope.launch {
                            try {
                                db.downloadDao().updateStateByUrl(
                                    url = item.url, state = "FAILED",
                                    ts = System.currentTimeMillis()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to update direct error: ${e.message}")
                            }
                        }
                        DownloadNotificationManager.showError(
                            item.url, safeFilename, error.message ?: "Erreur inconnue"
                        )
                    } catch (_: Exception) {}
                }
            }
        )
        return -1L
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun buildSubDir(item: MediaItem, naming: SmartNaming.NameResult? = null): String {
        val base = if (item.mediaType == MediaType.AUDIO) "HITIF/Audio" else "HITIF/Video"
        if (naming == null) return base
        val seriesSub = naming.seriesName
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.take(60)
        return when {
            seriesSub != null && naming.season != null ->
                "$base/$seriesSub/Season_${naming.season.toString().padStart(2, '0')}"
            seriesSub != null -> "$base/$seriesSub"
            else -> base
        }
    }

    private fun buildHeaders(item: MediaItem): Map<String, String> = buildMap {
        if (item.pageUrl.isNotBlank()) put("Referer", item.pageUrl)

        try {
            val cookieManager = CookieManager.getInstance()
            val cookieUrls = mutableListOf<String>()
            if (item.pageUrl.isNotBlank()) cookieUrls.add(item.pageUrl)
            try {
                val mediaHost = URL(item.url).host ?: ""
                if (mediaHost.isNotBlank() && item.url != item.pageUrl.substringBefore('/')) {
                    cookieUrls.add("${URL(item.url).protocol}://$mediaHost/")
                }
            } catch (_: Exception) {}

            val cookieBuilder = StringBuilder()
            for (cookieUrl in cookieUrls) {
                val cookies = cookieManager.getCookie(cookieUrl)
                if (!cookies.isNullOrBlank()) {
                    if (cookieBuilder.isNotEmpty()) cookieBuilder.append("; ")
                    cookieBuilder.append(cookies)
                }
            }
            val allCookies = cookieBuilder.toString().trim()
            if (allCookies.isNotEmpty()) {
                put("Cookie", allCookies)
                Log.d(TAG, "Cookies attached for: ${item.url.substringBefore('?').take(80)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract cookies: ${e.message}")
        }

        put("User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
    }

    private fun sanitizeFilename(name: String): String {
        return name.removeSuffix(".m3u8").removeSuffix(".mpd").removeSuffix(".m3u")
            .let { if (it.endsWith(".mp4")) it else "$it.mp4" }
    }

    /** Throttles DB writes to at most once per second */
    private class DbThrottler(
        private val db: AppDatabase,
        private val url: String,
        private val scope: CoroutineScope
    ) {
        @Volatile private var lastUpdate = 0L

        fun update(progress: TurboProgress) {
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 1_000L) {
                lastUpdate = now
                scope.launch {
                    try {
                        db.downloadDao().updateProgressByUrl(
                            url = url,
                            downloaded = progress.bytesDownloaded,
                            total = progress.totalBytes,
                            speed = progress.speedBps
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
