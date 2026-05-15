package com.hitif.videodownloader.download

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

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

    /** Active download count — used to manage the WakeLock lifecycle. */
    private val activeDownloadCount = AtomicInteger(0)
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var batteryExemptionRequested = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        DownloadNotificationManager.init(context)
        requestBatteryOptimizationExemption(context)
    }

    // -----------------------------------------------------------------------
    // WakeLock — keeps CPU alive during background downloads
    // -----------------------------------------------------------------------

    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "hitif:download_wakelock"
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L /* 30 min max, Android safety */)
            }
            Log.d(TAG, "WakeLock acquired for background downloads")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
        Log.d(TAG, "WakeLock released")
    }

    /** Called when a download starts — increments counter and acquires WakeLock. */
    fun onDownloadStarted(context: Context) {
        if (activeDownloadCount.incrementAndGet() == 1) {
            acquireWakeLock(context)
        }
    }

    /** Called when a download ends — decrements counter and releases WakeLock if idle. */
    fun onDownloadEnded() {
        if (activeDownloadCount.decrementAndGet() <= 0) {
            activeDownloadCount.set(0)
            releaseWakeLock()
        }
    }

    // -----------------------------------------------------------------------
    // Battery optimization exemption
    // -----------------------------------------------------------------------

    private fun requestBatteryOptimizationExemption(context: Context) {
        if (batteryExemptionRequested) return
        batteryExemptionRequested = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
            // Show a system dialog asking the user to allow background operation
            val intent = android.content.Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "Battery optimization exemption dialog shown")
        } catch (e: Exception) {
            Log.w(TAG, "Could not request battery exemption: ${e.message}")
        }
    }

    @Synchronized
    private fun ensureService(context: Context) {
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

    /**
     * Single download — starts immediately (bypasses semaphore).
     *
     * @param context  Application or activity context
     * @param item     The media item to download
     * @param customFilename  Optional custom base name (without extension).
     *                        If null, SmartNaming auto-generates the name.
     *                        The extension is always inferred from media type.
     */
    fun enqueue(
        context: android.content.Context,
        item: MediaItem,
        customFilename: String? = null
    ): Long {
        if (!initialized) init(context)
        val naming = SmartNaming.build(item)
        // If a custom filename is provided, override the auto-generated one
        val effectiveNaming = if (customFilename != null) {
            val ext = naming.extension
            naming.copy(
                filename = "$customFilename.$ext",
                baseName = customFilename
            )
        } else {
            naming
        }
        return startDownload(context, item, effectiveNaming, insertDb = true)
    }

    /**
     * Batch download (season packs, "Download All") with concurrency control.
     * Only MAX_CONCURRENT downloads run at a time; the rest wait in a queue.
     * All items appear immediately in history as QUEUED, then transition to
     * DOWNLOADING as slots become available.
     *
     * @param context  Application or activity context
     * @param items    The media items to download
     * @param customFilenames  Optional map of URL -> custom base name.
     *                          If a URL is present in the map, its custom name
     *                          is used; otherwise SmartNaming auto-generates.
     */
    fun enqueueBatch(
        context: android.content.Context,
        items: List<MediaItem>,
        customFilenames: Map<String, String> = emptyMap()
    ): List<Pair<MediaItem, Long>> {
        if (!initialized) init(context)
        val db = AppDatabase.getInstance(context)
        val results = mutableListOf<Pair<MediaItem, Long>>()

        for (item in items) {
            val naming = SmartNaming.build(item)
            // Override with custom filename if provided
            val effectiveNaming = if (customFilenames.containsKey(item.url)) {
                val custom = customFilenames[item.url]!!
                naming.copy(
                    filename = "${custom}.${naming.extension}",
                    baseName = custom
                )
            } else {
                naming
            }
            val safeFilename = when (item.mediaType) {
                MediaType.HLS -> sanitizeFilename(effectiveNaming.filename)
                else          -> effectiveNaming.filename
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

                    startDownload(context, item, effectiveNaming, insertDb = false)
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
        onDownloadStarted(context)

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
                    onDownloadEnded()
                    try {
                        runBlocking {
                            db.downloadDao().completeDownloadByUrl(
                                url = item.url, state = "COMPLETED",
                                ts = System.currentTimeMillis(), fileSize = file.length()
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to complete HLS record: ${e.message}")
                    }
                    try {
                        DownloadNotificationManager.showComplete(item.url, safeFilename, file.length())
                    } catch (_: Exception) {}
                }

                override fun onError(error: Throwable) {
                    onDownloadEnded()
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
        onDownloadStarted(context)

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
                    onDownloadEnded()
                    try {
                        runBlocking {
                            db.downloadDao().completeDownloadByUrl(
                                url = item.url, state = "COMPLETED",
                                ts = System.currentTimeMillis(), fileSize = file.length()
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to complete direct record: ${e.message}")
                    }
                    try {
                        DownloadNotificationManager.showComplete(item.url, safeFilename, file.length())

                        val ext = safeFilename.substringAfterLast('.', "mp4")
                        val mimeMap = mapOf(
                            "mp4"  to "video/mp4",
                            "mkv"  to "video/x-matroska",
                            "webm" to "video/webm",
                            "mp3"  to "audio/mpeg",
                            "m4a"  to "audio/mp4",
                            "ts"   to "video/mp2t"
                        )
                        android.media.MediaScannerConnection.scanFile(
                            context, arrayOf(file.absolutePath),
                            arrayOf(mimeMap[ext] ?: "video/mp4"), null
                        )
                    } catch (_: Exception) {}
                }

                override fun onError(error: Throwable) {
                    onDownloadEnded()
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
        // Strip playlist extensions — SmartNaming already provides the correct
        // output extension (mp4 for HLS/DASH, etc.) so we must NOT append .ts.
        return name.removeSuffix(".m3u8").removeSuffix(".mpd").removeSuffix(".m3u")
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
