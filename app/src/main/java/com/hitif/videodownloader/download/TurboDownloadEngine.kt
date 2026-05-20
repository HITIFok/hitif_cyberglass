package com.hitif.videodownloader.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------
// Data & callback definitions
// ---------------------------------------------------------------------------

data class TurboProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val percent: Int,
    val isActive: Boolean
)

interface TurboCallback {
    fun onProgress(progress: TurboProgress)
    fun onComplete(file: File)
    fun onError(error: Throwable)
}

// ---------------------------------------------------------------------------
// TurboDownloadEngine – multi-connection parallel downloader
// ---------------------------------------------------------------------------

object TurboDownloadEngine {

    private const val TAG = "TurboEngine"

    private const val CHUNK_COUNT = 4
    private const val MULTI_CHUNK_THRESHOLD = 2L * 1024 * 1024   // 2 MB
    private const val SPEED_SAMPLE_INTERVAL_MS = 500L
    private const val MAX_RESUME_ATTEMPTS = 8
    private const val RESUME_BACKOFF_BASE_MS = 3_000L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dns(FallbackDns())
            .retryOnConnectionFailure(true)
            .cookieJar(WebViewCookieJar())
            .build()
    }

    /** URL -> Job registry. Also used by DownloadProgressService to detect stale records. */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Returns the set of URLs currently being downloaded. */
    fun getActiveUrls(): Set<String> = activeJobs.keys.toHashSet()

    private data class DownloadAtomics(
        val totalDownloaded: AtomicLong = AtomicLong(0L),
        val previousDownloaded: AtomicLong = AtomicLong(0L)
    )

    // =========================================================================
    // Public API
    // =========================================================================

    fun download(
        context: Context,
        url: String,
        destPath: String,
        headers: Map<String, String> = emptyMap(),
        callback: TurboCallback
    ): Job {
        activeJobs[url]?.cancel()

        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught error for $url: ${throwable.message}", throwable)
            try { callback.onError(throwable) } catch (_: Exception) {}
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        val job = scope.launch {
            try {
                runDownload(context, url, destPath, headers, callback)
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled: $url")
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed: $url", e)
                try { callback.onError(e) } catch (_: Exception) {}
            } finally {
                activeJobs.remove(url)
            }
        }

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

    private suspend fun runDownload(
        context: Context,
        url: String,
        destPath: String,
        headers: Map<String, String>,
        callback: TurboCallback
    ) = coroutineScope {

        // ----- Step 0: Check YouTube URL expiration -----
        if (url.contains("googlevideo.com")) {
            val expireParam = Regex("[?&]expire=(\\d+)").find(url)
            if (expireParam != null) {
                try {
                    val expireEpoch = expireParam.groupValues[1].toLong()
                    val nowEpoch = System.currentTimeMillis() / 1000
                    val remaining = expireEpoch - nowEpoch
                    if (remaining < 0) {
                        throw IllegalStateException(
                            "URL YouTube expiree (il y a ${-remaining}s). " +
                            "Rafraichissez la page et reessayez."
                        )
                    }
                    if (remaining < 60) {
                        Log.w(TAG, "YouTube URL expires in ${remaining}s — download may fail")
                    }
                } catch (_: NumberFormatException) {}
            }
        }

        // ----- Step 0b: storage pre-check (floor: 100 MB minimum) -----
        val preCheck = StorageMonitor.checkSpace(-1L)
        if (preCheck is StorageMonitor.SpaceResult.InsufficientSpace) {
            throw IllegalStateException(preCheck.message)
        }

        // ----- Step 1: HEAD request for Content-Length & Content-Type -----
        var headContentType = ""
        val headRequest = Request.Builder()
            .url(url)
            .head()
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        val contentLength: Long = try {
            httpClient.newCall(headRequest).execute().use { resp ->
                if (!resp.isSuccessful) -1L
                else {
                    headContentType = resp.header("Content-Type", "") ?: ""
                    resp.body?.contentLength() ?: -1L
                }
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "HEAD DNS failed, retrying: ${e.message}")
            delay(2_000)
            try {
                httpClient.newCall(headRequest).execute().use { resp ->
                    if (!resp.isSuccessful) -1L
                    else {
                        headContentType = resp.header("Content-Type", "") ?: ""
                        resp.body?.contentLength() ?: -1L
                    }
                }
            } catch (_: Exception) { -1L }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD failed, single-chunk fallback: ${e.message}")
            -1L
        }

        // ----- Content-Type validation: reject HTML/JSON responses -----
        val ctLower = headContentType.lowercase()
        if (ctLower.contains("text/html") || ctLower.contains("application/json")) {
            throw IllegalStateException(
                "Le serveur retourne du ${headContentType.substringBefore(';')} au lieu du video. " +
                "URL probablement expiree ou invalide."
            )
        }

        // FIX #3: Emit a zero-progress callback immediately after learning
        // the content-length. This makes the DB (and UI) show the real file
        // size from the very first second, before any byte is downloaded.
        if (contentLength > 0L) {
            try {
                callback.onProgress(
                    TurboProgress(0L, contentLength, 0L, 0, true)
                )
            } catch (_: Exception) {}

            // Accurate space check now that we know the file size.
            val sizeCheck = StorageMonitor.checkSpace(contentLength)
            if (sizeCheck is StorageMonitor.SpaceResult.InsufficientSpace) {
                throw IllegalStateException(sizeCheck.message)
            }
        }

        // FIX #2: probeRangeSupport uses a real GET with Range: bytes=0-1.
        // Only HTTP 206 Partial Content confirms range support.
        // HTTP 200 means the server ignored the Range header → not supported.
        val supportsRange = if (contentLength > 0) probeRangeSupport(url, headers) else false

        if (contentLength <= 0L || !supportsRange) {
            singleChunkDownload(url, destPath, headers, null, callback)
            return@coroutineScope
        }

        val atomics = DownloadAtomics()
        if (contentLength > MULTI_CHUNK_THRESHOLD) {
            multiChunkDownload(context, url, destPath, headers, contentLength, atomics, callback)
        } else {
            singleChunkDownload(url, destPath, headers, atomics, callback)
        }
    }

    // -----------------------------------------------------------------------
    // FIX #2: Only HTTP 206 = genuine range support.
    // Use a tiny GET (bytes=0-1) instead of HEAD because some servers
    // respond correctly to GET ranges but not to HEAD with Range header.
    // -----------------------------------------------------------------------

    private suspend fun probeRangeSupport(url: String, headers: Map<String, String>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val probe = Request.Builder()
                    .url(url)
                    .get()
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .addHeader("Range", "bytes=0-1")
                    .build()

                httpClient.newCall(probe).execute().use { resp ->
                    // 206 = server correctly returns partial content
                    resp.code == 206
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Single-chunk (fallback) download
    // -----------------------------------------------------------------------

    private suspend fun singleChunkDownload(
        url: String,
        destPath: String,
        headers: Map<String, String>,
        atomics: DownloadAtomics?,
        callback: TurboCallback
    ) {
        val destFile = File(destPath)
        destFile.parentFile?.mkdirs()
        var downloaded = 0L
        var totalBytes = -1L
        var resumeAttempt = 0

        while (resumeAttempt <= MAX_RESUME_ATTEMPTS) {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .apply {
                            headers.forEach { (k, v) -> addHeader(k, v) }
                            // Resume from where we left off
                            if (downloaded > 0) addHeader("Range", "bytes=$downloaded-")
                        }
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        val statusCode = response.code

                        // Server returned 416 = range not satisfiable (file already complete)
                        if (statusCode == 416) {
                            if (totalBytes > 0) {
                                try {
                                    callback.onProgress(TurboProgress(totalBytes, totalBytes, 0L, 100, false))
                                    callback.onComplete(destFile)
                                } catch (_: Exception) {}
                                return@withContext
                            }
                        }

                        if (statusCode != 200 && statusCode != 206) {
                            throw IllegalStateException(
                                "Download failed: $statusCode ${response.message}"
                            )
                        }

                        val body = response.body
                            ?: throw IllegalStateException("Response body is null")

                        // Determine total size
                        if (totalBytes <= 0) {
                            totalBytes = body.contentLength()
                            // If server returned 206, total = content-range upper bound + 1
                            if (statusCode == 206) {
                                val contentRange = response.header("Content-Range")
                                val match = Regex("bytes \\d+-\\d+/(\\d+)").find(contentRange ?: "")
                                if (match != null) totalBytes = match.groupValues[1].toLong()
                            }
                        }

                        val isResume = statusCode == 206 && downloaded > 0
                        val writeMode = if (isResume) true else false // append if resuming

                        val prevRef = AtomicLong(downloaded)
                        var lastSpeedTime = System.currentTimeMillis()

                        body.byteStream().use { input ->
                            FileOutputStream(destFile, writeMode).use { output ->
                                val buffer = ByteArray(16_384)
                                while (true) {
                                    ensureActive()
                                    if (StorageMonitor.isCriticallyLow()) {
                                        throw IllegalStateException(
                                            "Espace critique — téléchargement annulé"
                                        )
                                    }
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    atomics?.totalDownloaded?.addAndGet(read.toLong())

                                    val now = System.currentTimeMillis()
                                    if (now - lastSpeedTime >= SPEED_SAMPLE_INTERVAL_MS) {
                                        val delta = downloaded - prevRef.getAndSet(downloaded)
                                        val elapsed = (now - lastSpeedTime) / 1000.0
                                        val speed = if (elapsed > 0) (delta / elapsed).toLong() else 0L
                                        val percent = if (totalBytes > 0)
                                            ((downloaded * 100) / totalBytes).toInt() else 0

                                        try {
                                            callback.onProgress(
                                                TurboProgress(downloaded, totalBytes, speed, percent, true)
                                            )
                                        } catch (_: Exception) {}
                                        lastSpeedTime = now
                                    }
                                }
                            }
                        }
                    }
                }
                // Download completed successfully (no exception)
                try {
                    callback.onProgress(TurboProgress(downloaded, totalBytes, 0L, 100, false))
                    callback.onComplete(destFile)
                } catch (_: Exception) {}
                return // exit the retry loop

            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val isRetryable = isRetryableNetworkError(e)
                resumeAttempt++

                if (!isRetryable || resumeAttempt > MAX_RESUME_ATTEMPTS) {
                    Log.e(TAG, "singleChunkDownload failed after $resumeAttempt attempts: ${e.message}", e)
                    try { callback.onError(e) } catch (_: Exception) {}
                    return
                }

                val backoff = (RESUME_BACKOFF_BASE_MS * resumeAttempt).coerceAtMost(15_000L)
                Log.w(TAG, "singleChunkDownload resume attempt $resumeAttempt/$MAX_RESUME_ATTEMPTS " +
                        "[${e.javaClass.simpleName}: ${e.message}] — waiting ${backoff}ms")

                // Notify user about retry
                try {
                    callback.onProgress(
                        TurboProgress(downloaded, totalBytes, 0L,
                            if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0, true)
                    )
                } catch (_: Exception) {}

                delay(backoff)
            }
        }
    }

    /**
     * Returns true if the error is a transient network error that can be
     * retried by resuming the download (e.g. connection dropped mid-stream).
     */
    private fun isRetryableNetworkError(e: Throwable): Boolean {
        val msg = e.message ?: ""
        return e is SocketException ||
               e is java.net.SocketTimeoutException ||
               e is java.net.ConnectException ||
               e is javax.net.ssl.SSLException ||
               msg.contains("unexpected end of stream", ignoreCase = true) ||
               msg.contains("connection reset", ignoreCase = true) ||
               msg.contains("broken pipe", ignoreCase = true) ||
               msg.contains("Connection closed prematurely", ignoreCase = true) ||
               msg.contains("stream closed", ignoreCase = true) ||
               msg.contains("Premature end of Content-Length", ignoreCase = true) ||
               msg.contains("closed", ignoreCase = true) ||
               msg.contains("timeout", ignoreCase = true) ||
               msg.contains("timed out", ignoreCase = true)
    }

    // -----------------------------------------------------------------------
    // Multi-chunk parallel download
    // -----------------------------------------------------------------------

    private suspend fun multiChunkDownload(
        context: Context,
        url: String,
        destPath: String,
        headers: Map<String, String>,
        contentLength: Long,
        atomics: DownloadAtomics,
        callback: TurboCallback
    ) = coroutineScope {

        val chunkSize = contentLength / CHUNK_COUNT
        val tempFiles = mutableListOf<File>()

        val speedJob = launch(Dispatchers.IO) {
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                delay(SPEED_SAMPLE_INTERVAL_MS)
                val nowBytes = atomics.totalDownloaded.get()
                val delta = nowBytes - lastBytes
                lastBytes = nowBytes
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime) / 1000.0
                lastTime = now
                val speed = if (elapsed > 0) (delta / elapsed).toLong() else 0L
                val percent = ((nowBytes * 100) / contentLength).toInt().coerceAtMost(100)

                try {
                    callback.onProgress(
                        TurboProgress(nowBytes, contentLength, speed, percent, true)
                    )
                } catch (_: Exception) {}
            }
        }

        try {
            val chunkDeferreds = (0 until CHUNK_COUNT).map { index ->
                async(Dispatchers.IO) {
                    val start = index * chunkSize
                    val end = if (index == CHUNK_COUNT - 1) contentLength - 1
                              else start + chunkSize - 1
                    val tempFile = File(
                        context.cacheDir,
                        "turbo_${System.nanoTime()}_${index}.tmp"
                    )
                    synchronized(tempFiles) { tempFiles.add(tempFile) }
                    downloadChunk(url, headers, start, end, tempFile, atomics)
                }
            }

            chunkDeferreds.awaitAll()

            val destFile = File(destPath)
            destFile.parentFile?.mkdirs()
            mergeFiles(tempFiles, destFile)
            cleanupTempFiles(tempFiles)

            speedJob.cancel()
            try {
                callback.onProgress(TurboProgress(contentLength, contentLength, 0L, 100, false))
                callback.onComplete(destFile)
            } catch (_: Exception) {}

        } catch (t: Throwable) {
            speedJob.cancel()
            cleanupTempFiles(tempFiles)
            if (t is CancellationException) throw t
            try { callback.onError(t) } catch (_: Exception) {}
        }
    }

    // -----------------------------------------------------------------------
    // Single byte-range chunk download
    // FIX #2: check Thread.interrupted() for cooperative cancellation of
    // blocking IO. OkHttp calls are blocking and don't respect coroutine
    // cancellation natively, so we poll the interrupt flag.
    // -----------------------------------------------------------------------

    private fun downloadChunk(
        url: String,
        headers: Map<String, String>,
        startByte: Long,
        endByte: Long,
        tempFile: File,
        atomics: DownloadAtomics
    ) {
        var bytesWritten = 0L
        var attempt = 0

        while (attempt <= MAX_RESUME_ATTEMPTS) {
            try {
                val rangeStart = startByte + bytesWritten
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .addHeader("Range", "bytes=$rangeStart-$endByte")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.code != 206 && response.code != 200) {
                        throw IllegalStateException(
                            "Chunk failed range $rangeStart-$endByte: " +
                            "${response.code} ${response.message}"
                        )
                    }

                    val body = response.body
                        ?: throw IllegalStateException(
                            "Chunk body null for range $rangeStart-$endByte"
                        )

                    body.byteStream().use { input ->
                        tempFile.parentFile?.mkdirs()
                        val append = bytesWritten > 0
                        FileOutputStream(tempFile, append).use { output ->
                            val buffer = ByteArray(16_384)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) {
                                    throw InterruptedException(
                                        "Chunk $rangeStart-$endByte interrupted"
                                    )
                                }
                                if (StorageMonitor.isCriticallyLow()) {
                                    throw IllegalStateException(
                                        "Espace critique — téléchargement annulé"
                                    )
                                }
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                bytesWritten += read
                                atomics.totalDownloaded.addAndGet(read.toLong())
                            }
                        }
                    }
                }
                return // chunk completed successfully

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                val isRetryable = isRetryableNetworkError(e)
                attempt++

                if (!isRetryable || attempt > MAX_RESUME_ATTEMPTS) {
                    Log.e(TAG, "downloadChunk failed after $attempt attempts range $startByte-$endByte: ${e.message}")
                    throw e
                }

                val backoff = (RESUME_BACKOFF_BASE_MS * attempt).coerceAtMost(15_000L)
                Log.w(TAG, "downloadChunk retry $attempt/$MAX_RESUME_ATTEMPTS " +
                        "range $startByte-$endByte [${e.javaClass.simpleName}: ${e.message}] — wait ${backoff}ms")
                try { Thread.sleep(backoff) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Merge ordered temp files into the destination file
    // -----------------------------------------------------------------------

    private fun mergeFiles(sourceFiles: List<File>, destFile: File) {
        FileOutputStream(destFile).use { output ->
            sourceFiles.forEach { file ->
                if (!file.exists()) return@forEach
                file.inputStream().use { input ->
                    val buffer = ByteArray(16_384)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun cleanupTempFiles(files: List<File>) {
        files.forEach { file ->
            try { if (file.exists()) file.delete() } catch (_: Exception) {}
        }
    }

    // -----------------------------------------------------------------------
    // Content-sniffing: validate that downloaded bytes are actual video/audio,
    // not an HTML error page or JSON error response.
    // Inspired by VidMate's content validation approach.
    // -----------------------------------------------------------------------

    /**
     * Check the first bytes of a file to verify it's actual media content.
     * Returns true if magic bytes match a known media format.
     */
    fun sniffFileContent(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        try {
            file.inputStream().use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                if (read < 4) return false

                // MP4: ftyp box — offset 4-7 should be "ftyp"
                if (read >= 8 && header[4] == 'f'.code.toByte() &&
                    header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() &&
                    header[7] == 'p'.code.toByte()) {
                    return true
                }

                // WebM/Matroska: 0x1A 0x45 0xDF 0xA3
                if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                    header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()) {
                    return true
                }

                // FLV: 0x46 0x4C 0x56 ("FLV")
                if (header[0] == 0x46.toByte() && header[1] == 0x4C.toByte() &&
                    header[2] == 0x56.toByte()) {
                    return true
                }

                // MPEG-TS: 0x47 (sync byte)
                if (header[0] == 0x47.toByte()) {
                    return true
                }

                // MP3: 0xFF 0xFB or 0xFF 0xF3 or 0xFF 0xF2 (MPEG audio frame sync)
                if (header[0] == 0xFF.toByte() &&
                    (header[1].toInt() and 0xE0) == 0xE0) {
                    return true
                }

                // RIFF/AVI/WAV: "RIFF"...."AVI " or "WAVE"
                if (read >= 12 && header[0] == 'R'.code.toByte() &&
                    header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() &&
                    header[3] == 'F'.code.toByte()) {
                    return true
                }

                // OGG: "OggS"
                if (read >= 4 && header[0] == 'O'.code.toByte() &&
                    header[1] == 'g'.code.toByte() &&
                    header[2] == 'g'.code.toByte() &&
                    header[3] == 'S'.code.toByte()) {
                    return true
                }

                // Check for text content (HTML/JSON error pages)
                val headerStr = String(header, 0, read.coerceAtMost(12), Charsets.US_ASCII)
                val headerLower = headerStr.lowercase()
                if (headerLower.startsWith("<!doctype") || headerLower.startsWith("<html") ||
                    headerLower.startsWith("{\"") || headerLower.startsWith("{")) {
                    Log.w(TAG, "Content-sniffing: file starts with text marker: $headerStr")
                    return false
                }

                // If no known format detected but also not text, allow it
                // (some formats don't have clear magic bytes)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Content-sniffing error: ${e.message}")
            return true // Don't fail on sniff error
        }
    }

    /**
     * Validate a completed download: check file isn't suspiciously small
     * and content matches expected format.
     */
    fun validateDownload(destPath: String, expectedSize: Long): Boolean {
        val file = File(destPath)
        if (!file.exists()) return false

        val fileSize = file.length()

        // If we know the expected size, check the file is at least 90% of it
        if (expectedSize > 0) {
            if (fileSize < expectedSize * 0.9) {
                Log.w(TAG, "Validation: file too small ${fileSize}/${expectedSize} bytes")
                return false
            }
        }

        // For very small files (< 1KB), likely an error page
        if (fileSize < 1024) {
            return sniffFileContent(file)
        }

        return true
    }
}
