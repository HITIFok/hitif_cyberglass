package com.hitif.videodownloader.download

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * HLS Stream Downloader — parses .m3u8 playlists, downloads all segments,
 * and merges them into a single playable .mp4/.ts file.
 *
 * Flow:
 *   1. Fetch master.m3u8 → select highest bandwidth variant
 *   2. Fetch variant playlist (index.m3u8) → extract segment URLs
 *   3. Warm DNS for segment hostnames via WebView
 *   4. Download segments in parallel (4 connections)
 *   5. Merge into final file in Download/HITIF/Video/
 *
 * DNS fallback: if OkHttp cannot resolve the proxy hostname, the caller
 * (DownloadHelper) can pre-fetch the m3u8 content via WebViewFetchHelper
 * and pass it using [downloadWithContent].
 */
object HlsDownloader {

    private const val TAG = "HlsDownloader"
    private const val PARALLEL_SEGMENTS = 4
    private const val BUFFER_SIZE = 8192
    private const val SPEED_SAMPLE_MS = 500L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dns(FallbackDns())
            .retryOnConnectionFailure(true)
            .cookieJar(WebViewCookieJar())
            .build()
    }

    // Track active jobs for cancellation and stale-detection
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Returns the set of HLS URLs currently being downloaded.
     *  Used by DownloadProgressService to avoid false-positive FAILED detection. */
    fun getActiveUrls(): Set<String> = activeJobs.keys.toHashSet()

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Download an HLS stream from a .m3u8 URL.
     */
    fun download(
        context: Context,
        m3u8Url: String,
        filename: String,
        subDir: String = "HITIF/Video",
        headers: Map<String, String> = emptyMap(),
        callback: TurboCallback
    ): Job {
        return startJob(m3u8Url) {
            runHlsDownload(context, m3u8Url, null, filename, subDir, headers, callback)
        }
    }

    /**
     * Download an HLS stream using pre-fetched m3u8 content.
     * Use this when OkHttp DNS fails but the content was fetched via WebView.
     *
     * @param preFetchedContent  The m3u8 playlist text content
     * @param originalUrl        The original m3u8 URL (used as base URL for relative segments)
     */
    fun downloadWithContent(
        context: Context,
        originalUrl: String,
        preFetchedContent: String,
        filename: String,
        subDir: String = "HITIF/Video",
        headers: Map<String, String> = emptyMap(),
        callback: TurboCallback
    ): Job {
        return startJob(originalUrl) {
            runHlsDownload(context, originalUrl, preFetchedContent, filename, subDir, headers, callback)
        }
    }

    private fun startJob(url: String, block: suspend CoroutineScope.() -> Unit): Job {
        activeJobs[url]?.cancel()

        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught error for $url: ${throwable.message}", throwable)
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        val job = scope.launch(block = block)
        job.invokeOnCompletion { activeJobs.remove(url) }
        activeJobs[url] = job
        return job
    }

    fun cancel(url: String) {
        activeJobs[url]?.cancel()
        activeJobs.remove(url)
    }

    fun cancelAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    // =========================================================================
    // Internal implementation
    // =========================================================================

    private suspend fun runHlsDownload(
        context: Context,
        m3u8Url: String,
        preFetchedContent: String?,
        filename: String,
        subDir: String,
        headers: Map<String, String>,
        callback: TurboCallback
    ) = coroutineScope {

        // Step 1: Get playlist content — use pre-fetched or fetch via OkHttp
        val playlistBody = preFetchedContent ?: fetchPlaylistWithFallback(m3u8Url, headers)
            ?: throw IllegalStateException("Impossible de recuperer le playlist m3u8. Verifiez votre connexion.")

        val lines = playlistBody.lines()
        Log.d(TAG, "m3u8 content received: ${lines.size} lines for $filename")

        // Check if this is a master playlist
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }

        val segmentUrls: List<String>
        val baseUrl: String

        if (isMaster) {
            val variant = parseMasterPlaylist(lines)
            Log.d(TAG, "Master playlist: bandwidth=${variant.bandwidth} url=${variant.url}")
            // Fetch variant playlist (try OkHttp, fallback to WebView)
            val variantUrl = resolveUrl(m3u8Url, variant.url)
            val variantBody = fetchPlaylistWithFallback(variantUrl, headers)
                ?: throw IllegalStateException("Impossible de recuperer le variant playlist.")
            val variantLines = variantBody.lines()
            baseUrl = variantUrl.substringBeforeLast('/') + "/"
            segmentUrls = extractSegments(variantLines, baseUrl)
        } else {
            baseUrl = m3u8Url.substringBeforeLast('/') + "/"
            segmentUrls = extractSegments(lines, baseUrl)
        }

        if (segmentUrls.isEmpty()) {
            throw IllegalStateException("Aucun segment trouve dans le playlist HLS")
        }

        Log.d(TAG, "Found ${segmentUrls.size} segments for $filename")

        // Step 2: Warm DNS for segment hostnames via WebView
        // Chrome has already resolved the m3u8 host; this warms the DNS for segment hosts too
        val segmentHosts = segmentUrls.mapNotNull { try { URL(it).host } catch (_: Exception) { null } }.distinct()
        for (host in segmentHosts.take(3)) {
            WebViewFetchHelper.warmDns("https://$host/")
        }

        // Step 3: Download segments in parallel
        val totalSegments = segmentUrls.size
        val totalBytes = AtomicLong(0L)
        val completedSegments = java.util.concurrent.atomic.AtomicInteger(0)

        // Speed tracking
        val speedJob = launch(Dispatchers.IO) {
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L
            while (isActive) {
                delay(SPEED_SAMPLE_MS)
                val nowBytes = totalBytes.get()
                val delta = (nowBytes - lastBytes).coerceAtLeast(0L)
                lastBytes = nowBytes
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime) / 1000.0
                lastTime = now
                val speed = if (elapsed > 0) (delta / elapsed).toLong() else 0L
                val done = completedSegments.get()
                val segPercent = ((done * 100) / totalSegments).toInt().coerceIn(0, 95)

                try {
                    callback.onProgress(
                        TurboProgress(nowBytes, -1L, speed, segPercent, true)
                    )
                } catch (_: Exception) {}
            }
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outputDir = File(downloadsDir, subDir)
        outputDir.mkdirs()
        val tempFiles = mutableListOf<File>()

        try {
            val batches = segmentUrls.chunked(PARALLEL_SEGMENTS)
            for ((batchIdx, batch) in batches.withIndex()) {
                val deferreds = batch.mapIndexed { segIdx, segUrl ->
                    async(Dispatchers.IO) {
                        val globalIdx = batchIdx * PARALLEL_SEGMENTS + segIdx
                        val tempFile = File(context.cacheDir, "hls_${m3u8Url.hashCode()}_seg_$globalIdx.tmp")
                        tempFiles.add(tempFile)
                        downloadSegment(segUrl, headers, tempFile, totalBytes)
                        completedSegments.incrementAndGet()
                    }
                }
                deferreds.awaitAll()
            }

            // Step 4: Merge
            speedJob.cancel()
            val outputFile = File(outputDir, filename)
            mergeSegmentFiles(tempFiles, outputFile)
            val finalSize = outputFile.length()
            Log.d(TAG, "HLS complete: $filename (${finalSize} bytes)")

            val ext = filename.substringAfterLast('.', "mp4")
            val mimeMap = mapOf("mp4" to "video/mp4", "mkv" to "video/x-matroska", "ts" to "video/mp2t")
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(outputFile.absolutePath),
                arrayOf(mimeMap[ext] ?: "video/mp4"), null
            )

            try {
                callback.onProgress(TurboProgress(finalSize, finalSize, 0L, 100, false))
                callback.onComplete(outputFile)
            } catch (_: Exception) {}

        } catch (e: CancellationException) {
            speedJob.cancel()
            throw e
        } catch (e: Exception) {
            speedJob.cancel()
            tempFiles.forEach { try { it.delete() } catch (_: Exception) {} }
            try { callback.onError(e) } catch (_: Exception) {}
        }
    }

    // -----------------------------------------------------------------------
    // Fetch playlist — try OkHttp first, then WebView fallback
    // -----------------------------------------------------------------------

    /**
     * Try OkHttp first, then fall back to WebViewFetchHelper if DNS fails.
     */
    private suspend fun fetchPlaylistWithFallback(url: String, headers: Map<String, String>): String? {
        // 1. Try OkHttp with retries
        try {
            return fetchPlaylist(url, headers)
        } catch (e: Exception) {
            Log.w(TAG, "OkHttp fetch failed for ${url.take(80)}: ${e.message}")
        }

        // 2. Try WebView XHR (Chrome can resolve private DNS hostnames)
        Log.d(TAG, "Trying WebView XHR fallback for ${url.take(80)}")
        val referer = headers["Referer"] ?: ""
        val content = WebViewFetchHelper.fetchContent(url, referer)
        if (content != null && content.contains("#EXT")) {
            Log.d(TAG, "WebView XHR succeeded: ${content.length} chars")
            return content
        }

        // 3. Try one more time with OkHttp after DNS warm-up
        try {
            Log.d(TAG, "Final OkHttp retry after WebView DNS warm-up")
            delay(2000)
            return fetchPlaylist(url, headers)
        } catch (e: Exception) {
            Log.e(TAG, "All fetch methods failed for ${url.take(80)}: ${e.message}")
            return null
        }
    }

    /**
     * Fetch playlist via OkHttp with aggressive retry (ALL exceptions retryable).
     */
    private suspend fun fetchPlaylist(url: String, headers: Map<String, String>): String {
        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            val maxAttempts = 3

            for (attempt in 1..maxAttempts) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .apply {
                            headers.forEach { (k, v) ->
                                if (k.lowercase() != "host") addHeader(k, v)
                            }
                            header("User-Agent",
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                        }
                        .build()

                    httpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("HTTP ${resp.code} for $url")
                        }
                        resp.body?.string()
                            ?: throw IllegalStateException("Empty body for $url")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    val isDns = e is UnknownHostException || e.message?.contains("resolve host", ignoreCase = true) == true
                    val isTimeout = e is java.net.SocketTimeoutException ||
                            e.message?.contains("timeout", ignoreCase = true) == true ||
                            e.message?.contains("timed out", ignoreCase = true) == true

                    val backoff = when {
                        isDns -> (2000L * attempt).coerceAtMost(8000L)
                        isTimeout -> (2000L * attempt).coerceAtMost(8000L)
                        else -> (1000L * attempt).coerceAtMost(5000L)
                    }

                    Log.w(TAG, "fetchPlaylist attempt $attempt/$maxAttempts failed [${
                        when {
                            isDns -> "DNS"
                            isTimeout -> "TIMEOUT"
                            else -> e.javaClass.simpleName
                        }
                    }]: ${e.message}")

                    if (attempt < maxAttempts) delay(backoff)
                }
            }
            throw lastError ?: IllegalStateException("Failed to fetch playlist after $maxAttempts attempts: $url")
        }
    }

    // -----------------------------------------------------------------------
    // Parse master playlist
    // -----------------------------------------------------------------------

    private data class Variant(val bandwidth: Int, val url: String)

    private fun parseMasterPlaylist(lines: List<String>): Variant {
        var maxBandwidth = -1
        var selectedUrl = ""

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bwMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                val bandwidth = bwMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val nextIdx = i + 1
                if (nextIdx < lines.size) {
                    val urlLine = lines[nextIdx].trim()
                    if (urlLine.isNotEmpty() && !urlLine.startsWith("#") && bandwidth > maxBandwidth) {
                        maxBandwidth = bandwidth
                        selectedUrl = urlLine
                    }
                }
            }
            i++
        }

        if (selectedUrl.isBlank()) {
            throw IllegalStateException("No variants found in master playlist")
        }
        return Variant(maxBandwidth, selectedUrl)
    }

    // -----------------------------------------------------------------------
    // Extract segments
    // -----------------------------------------------------------------------

    private fun extractSegments(lines: List<String>, baseUrl: String): List<String> {
        val segments = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val fullUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                baseUrl.trimEnd('/') + "/" + trimmed.trimStart('/')
            }
            segments.add(fullUrl)
        }
        return segments
    }

    private fun resolveUrl(base: String, relative: String): String {
        return if (relative.startsWith("http://") || relative.startsWith("https://")) {
            relative
        } else {
            val baseDir = base.substringBeforeLast('/')
            "$baseDir/${relative.trimStart('/')}"
        }
    }

    // -----------------------------------------------------------------------
    // Download segment — ALL exceptions retryable
    // -----------------------------------------------------------------------

    private suspend fun downloadSegment(
        url: String,
        headers: Map<String, String>,
        destFile: File,
        totalBytes: AtomicLong
    ) {
        withContext(Dispatchers.IO) {
            var retryCount = 0
            val maxRetries = 5

            while (retryCount <= maxRetries) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .apply {
                            headers.forEach { (k, v) ->
                                if (k.lowercase() != "host") addHeader(k, v)
                            }
                            header("User-Agent",
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                        }
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("HTTP ${response.code} for segment $url")
                        }
                        val body = response.body
                            ?: throw IllegalStateException("Null body for segment: $url")

                        destFile.parentFile?.mkdirs()
                        body.byteStream().use { input ->
                            FileOutputStream(destFile).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    ensureActive()
                                    output.write(buffer, 0, read)
                                    totalBytes.addAndGet(read.toLong())
                                }
                            }
                        }
                    }
                    return@withContext

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount > maxRetries) {
                        Log.e(TAG, "Segment failed after $maxRetries retries: ${url.take(60)} — ${e.message}")
                        throw e
                    }
                    val isDns = e is UnknownHostException || e.message?.contains("resolve host", ignoreCase = true) == true
                    val backoff = if (isDns) {
                        (3000L * retryCount).coerceAtMost(15000L)
                    } else {
                        (1000L * retryCount).coerceAtMost(5000L)
                    }
                    Log.w(TAG, "Segment retry $retryCount/$maxRetries [${e.javaClass.simpleName}]: ${url.take(60)} — wait ${backoff}ms")
                    delay(backoff)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Merge segments
    // -----------------------------------------------------------------------

    private fun mergeSegmentFiles(segmentFiles: List<File>, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { output ->
            for (file in segmentFiles) {
                if (!file.exists()) continue
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        segmentFiles.forEach { try { it.delete() } catch (_: Exception) {} }
    }
}
