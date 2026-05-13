package com.hitif.videodownloader.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
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

        // ----- Step 0: storage pre-check (floor: 100 MB minimum) -----
        val preCheck = StorageMonitor.checkSpace(-1L)
        if (preCheck is StorageMonitor.SpaceResult.InsufficientSpace) {
            throw IllegalStateException(preCheck.message)
        }

        // ----- Step 1: HEAD request for Content-Length -----
        val headRequest = Request.Builder()
            .url(url)
            .head()
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        val contentLength: Long = try {
            httpClient.newCall(headRequest).execute().use { resp ->
                if (!resp.isSuccessful) -1L
                else resp.body?.contentLength() ?: -1L
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "HEAD DNS failed, retrying: ${e.message}")
            delay(2_000)
            try {
                httpClient.newCall(headRequest).execute().use { resp ->
                    if (!resp.isSuccessful) -1L else resp.body?.contentLength() ?: -1L
                }
            } catch (_: Exception) { -1L }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD failed, single-chunk fallback: ${e.message}")
            -1L
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
        try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException(
                            "Download failed: ${response.code} ${response.message}"
                        )
                    }

                    val body = response.body
                        ?: throw IllegalStateException("Response body is null")

                    val totalBytes = body.contentLength()
                    val destFile = File(destPath)
                    destFile.parentFile?.mkdirs()

                    var downloaded = 0L
                    val prevRef = AtomicLong(0L)
                    var lastSpeedTime = System.currentTimeMillis()

                    body.byteStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(16_384)
                            while (true) {
                                ensureActive()
                                // FIX #4: abort if disk fills up mid-download
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

                    try {
                        callback.onProgress(TurboProgress(downloaded, totalBytes, 0L, 100, false))
                        callback.onComplete(destFile)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "singleChunkDownload error: ${e.message}", e)
            try { callback.onError(e) } catch (_: Exception) {}
        }
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
                        "turbo_${url.hashCode()}_chunk_$index.tmp"
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
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .addHeader("Range", "bytes=$startByte-$endByte")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    throw IllegalStateException(
                        "Chunk failed range $startByte-$endByte: " +
                        "${response.code} ${response.message}"
                    )
                }

                val body = response.body
                    ?: throw IllegalStateException(
                        "Chunk body null for range $startByte-$endByte"
                    )

                body.byteStream().use { input ->
                    tempFile.parentFile?.mkdirs()
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16_384)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedException(
                                    "Chunk $startByte-$endByte interrupted"
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
                            atomics.totalDownloaded.addAndGet(read.toLong())
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "downloadChunk error range $startByte-$endByte: ${e.message}")
            throw e
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
}
