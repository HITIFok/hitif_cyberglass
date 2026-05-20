package com.hitif.videodownloader.download

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTubeDownloadTask — Telechargement robuste des videos YouTube.
 *
 * Utilise HttpURLConnection avec les headers corrects du client YouTube Android.
 * Gere les redirections manuellement, supporte la reprise (Range requests),
 * et fusionne audio+video DASH avec MediaMuxer (pas de FFmpeg requis).
 */
class YouTubeDownloadTask {

    companion object {
        private const val TAG = "YTDownloadTask"
        private const val BUFFER_SIZE = 128 * 1024
        private const val MAX_REDIRECTS = 5

        private const val YT_ANDROID_UA =
            "com.google.android.youtube/19.29.37 (Linux; U; Android 11; en_US) gzip"

        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L

        private fun buildDownloadHeaders(cookies: String?): Map<String, String> {
            val headers = mutableMapOf(
                "User-Agent"       to YT_ANDROID_UA,
                "Accept"           to "*/*",
                "Accept-Language"  to "en-US,en;q=0.9",
                "Accept-Encoding"  to "identity",
                "Origin"           to "https://www.youtube.com",
                "Referer"          to "https://www.youtube.com/",
                "Sec-Fetch-Dest"   to "video",
                "Sec-Fetch-Mode"   to "no-cors",
                "Sec-Fetch-Site"   to "cross-site",
                "Connection"       to "keep-alive"
            )
            if (!cookies.isNullOrEmpty()) {
                headers["Cookie"] = cookies
            }
            return headers
        }

        /**
         * Sniff the first bytes of a stream to detect if it's a media file
         * or an HTML/JSON error response.
         */
        private fun isMediaContent(firstBytes: ByteArray, bytesRead: Int): Boolean {
            if (bytesRead < 4) return false
            // MP4: ftyp box
            if (bytesRead >= 8 &&
                firstBytes[4] == 'f'.code.toByte() &&
                firstBytes[5] == 't'.code.toByte() &&
                firstBytes[6] == 'y'.code.toByte() &&
                firstBytes[7] == 'p'.code.toByte()) return true
            // WebM/Matroska: 0x1A 0x45 0xDF 0xA3
            if (firstBytes[0] == 0x1A.toByte() && firstBytes[1] == 0x45.toByte() &&
                firstBytes[2] == 0xDF.toByte() && firstBytes[3] == 0xA3.toByte()) return true
            // FLV: 0x46 0x4C 0x56
            if (firstBytes[0] == 0x46.toByte() && firstBytes[1] == 0x4C.toByte() &&
                firstBytes[2] == 0x56.toByte()) return true
            // MPEG-TS: 0x47
            if (firstBytes[0] == 0x47.toByte()) return true
            // MP3: 0xFF 0xFB/F3/F2
            if (firstBytes[0] == 0xFF.toByte() && (firstBytes[1].toInt() and 0xE0) == 0xE0) return true
            // RIFF/AVI/WAV
            if (bytesRead >= 12 &&
                firstBytes[0] == 'R'.code.toByte() && firstBytes[1] == 'I'.code.toByte() &&
                firstBytes[2] == 'I'.code.toByte() && firstBytes[3] == 'F'.code.toByte()) return true
            // OGG
            if (firstBytes[0] == 'O'.code.toByte() && firstBytes[1] == 'g'.code.toByte() &&
                firstBytes[2] == 'g'.code.toByte() && firstBytes[3] == 'S'.code.toByte()) return true

            // Check for text content (HTML/JSON error pages)
            val headerStr = String(firstBytes, 0, minOf(bytesRead, 12), Charsets.US_ASCII)
            val headerLower = headerStr.lowercase()
            if (headerLower.startsWith("<!doctype") || headerLower.startsWith("<html") ||
                headerLower.startsWith("{\"") || headerLower.startsWith("{")) {
                Log.w(TAG, "Content-sniffing: first bytes are text, NOT media: $headerStr")
                return false
            }
            // If no known format detected but also not text, allow it
            return true
        }

        /**
         * Validate a downloaded file: check it's not suspiciously small
         * and content matches expected format.
         */
        fun validateDownloadedFile(file: File): Boolean {
            if (!file.exists()) return false
            val fileSize = file.length()
            // Very small files (< 10KB) from YouTube are likely error responses
            if (fileSize < 10_240) {
                Log.w(TAG, "Validation: file suspiciously small (${fileSize}B) — checking content")
                return isMediaContent(file.readBytes(), fileSize.toInt())
            }
            // For larger files, check magic bytes
            try {
                val header = ByteArray(16)
                file.inputStream().use { input ->
                    val read = input.read(header)
                    return isMediaContent(header, read)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Validation: read error: ${e.message}")
                return true // Don't fail on read error
            }
        }
    }

    interface ProgressCallback {
        fun onProgress(percent: Int, downloadedBytes: Long, totalBytes: Long)
        fun onSuccess(file: File)
        fun onError(message: String)
        fun onMerging()
    }

    /**
     * Telecharge une video YouTube avec la strategie optimale.
     */
    suspend fun download(
        context: Context,
        result: YouTubeExtractor.ExtractionResult,
        outputDir: File,
        fileName: String,
        preferHighQuality: Boolean = false,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        val cookies = getYouTubeCookies()
        Log.d(TAG, "Cookies YouTube disponibles: ${!cookies.isNullOrEmpty()}")

        when (val strategy = result.selectBestForDownload(preferHighQuality)) {
            is YouTubeExtractor.DownloadStrategy.Muxed -> {
                Log.d(TAG, "Strategie: MUXED — ${strategy.stream.qualityLabel} (${strategy.stream.fileExtension})")
                val file = File(outputDir, "$fileName.${strategy.stream.fileExtension}")
                downloadSingleStream(strategy.stream.url, file, cookies, callback)
            }

            is YouTubeExtractor.DownloadStrategy.Adaptive -> {
                Log.d(TAG, "Strategie: DASH — video=${strategy.video.qualityLabel} + audio")
                downloadAndMergeDash(
                    strategy.video, strategy.audio, outputDir, fileName, cookies, callback
                )
            }

            is YouTubeExtractor.DownloadStrategy.Hls -> {
                Log.d(TAG, "Strategie: HLS fallback")
                callback.onError("HLS_FALLBACK:${strategy.manifestUrl}")
            }

            is YouTubeExtractor.DownloadStrategy.Error -> {
                callback.onError(strategy.message)
            }
        }
    }

    private suspend fun downloadSingleStream(
        url: String,
        outputFile: File,
        cookies: String?,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        val tempFile = File(outputFile.parent, "${outputFile.name}.tmp")

        var lastError: String? = null

        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            try {
                if (attempt > 1) {
                    Log.d(TAG, "Tentative $attempt/$MAX_DOWNLOAD_ATTEMPTS apres erreur: $lastError")
                    delay(RETRY_DELAY_MS * attempt)
                    tempFile.delete()
                }

                val totalBytes = getContentLength(url, cookies)
                var downloadedBytes = 0L

                // Reprise si fichier temporaire existant
                val startByte = if (tempFile.exists() && attempt == 1) tempFile.length() else 0L
                if (startByte > 0 && startByte < totalBytes) {
                    Log.d(TAG, "Reprise du telechargement depuis $startByte bytes")
                } else {
                    tempFile.delete()
                }

                openConnection(url, cookies, rangeStart = startByte).use { (connection, stream) ->
                    // First-byte content sniffing: read first 16 bytes to check it's media
                    val firstBytes = ByteArray(16)
                    val bytesRead = stream.read(firstBytes)
                    if (bytesRead > 0) {
                        if (!isMediaContent(firstBytes, bytesRead)) {
                            connection.disconnect()
                            val errorMsg = buildString {
                                append("Le serveur retourne du contenu non-media (probablement HTML/JSON erreur). ")
                                append("URL YouTube probablement expiree. Rechargez la page video et reessayez.")
                            }
                            Log.e(TAG, errorMsg)
                            lastError = errorMsg
                            tempFile.delete()
                            // Retry — the URL might have expired between extraction and download
                            continue
                        }
                        // Write the first bytes we already read
                        FileOutputStream(tempFile, startByte > 0).use { fos ->
                            fos.write(firstBytes, 0, bytesRead)
                            downloadedBytes = startByte + bytesRead

                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (stream.read(buffer).also { read = it } != -1) {
                                if (!isActive) {
                                    Log.d(TAG, "Telechargement annule")
                                    return@withContext
                                }
                                fos.write(buffer, 0, read)
                                downloadedBytes += read
                                if (totalBytes > 0) {
                                    val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                    callback.onProgress(percent, downloadedBytes, totalBytes)
                                }
                            }
                        }
                    } else {
                        // Empty response
                        FileOutputStream(tempFile, startByte > 0).use { fos ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (stream.read(buffer).also { read = it } != -1) {
                                if (!isActive) {
                                    Log.d(TAG, "Telechargement annule")
                                    return@withContext
                                }
                                fos.write(buffer, 0, read)
                                downloadedBytes += read
                                if (totalBytes > 0) {
                                    val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                    callback.onProgress(percent, downloadedBytes, totalBytes)
                                }
                            }
                        }
                    }
                    connection.disconnect()
                }

                // Post-download validation: verify file is actual media
                if (tempFile.exists()) {
                    if (!validateDownloadedFile(tempFile)) {
                        lastError = "Le fichier telecharge n'est pas un media valide (contenu errone). URL probablement expiree."
                        Log.e(TAG, lastError)
                        tempFile.delete()
                        continue
                    }

                    tempFile.renameTo(outputFile)
                    Log.d(TAG, "Telechargement termine: ${outputFile.absolutePath} (${outputFile.length() / 1024}KB)")
                    callback.onProgress(100, outputFile.length(), outputFile.length())
                    callback.onSuccess(outputFile)
                    return@withContext // Success — exit retry loop
                } else {
                    callback.onError("Fichier temporaire introuvable")
                    return@withContext
                }

            } catch (e: Exception) {
                val msg = e.message ?: "Erreur inconnue"
                Log.e(TAG, "Erreur telechargement (tentative $attempt/$MAX_DOWNLOAD_ATTEMPTS): $msg", e)
                lastError = msg
                tempFile.delete()
            }
        }

        // All attempts failed
        val finalError = buildString {
            append("Echec apres $MAX_DOWNLOAD_ATTEMPTS tentatives. ")
            append(lastError ?: "Erreur inconnue")
            append(". Astuce: rechargez la page YouTube et reessayez.")
        }
        callback.onError(finalError)
    }

    private suspend fun downloadAndMergeDash(
        video: YouTubeExtractor.YouTubeStream,
        audio: YouTubeExtractor.YouTubeStream,
        outputDir: File,
        fileName: String,
        cookies: String?,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        outputDir.mkdirs()

        val videoTemp = File(outputDir, "${fileName}_video.tmp")
        val audioTemp = File(outputDir, "${fileName}_audio.tmp")
        val outputFile = File(outputDir, "$fileName.mp4")

        try {
            // Etape 1/3: Telecharger la video (0-60%)
            Log.d(TAG, "DASH Step 1/3: Telechargement video (${video.qualityLabel})")
            downloadStreamToFile(video.url, videoTemp, cookies) { percent ->
                callback.onProgress((percent * 0.6).toInt(), 0, 0)
            }

            // Etape 2/3: Telecharger l'audio (60-90%)
            Log.d(TAG, "DASH Step 2/3: Telechargement audio (${audio.qualityLabel})")
            downloadStreamToFile(audio.url, audioTemp, cookies) { percent ->
                callback.onProgress(60 + (percent * 0.3).toInt(), 0, 0)
            }

            // Etape 3/3: Fusion MediaMuxer (90-100%)
            Log.d(TAG, "DASH Step 3/3: Fusion audio+video → $outputFile")
            callback.onMerging()
            callback.onProgress(90, 0, 0)

            val success = mergeWithMediaMuxer(videoTemp, audioTemp, outputFile)

            if (success) {
                videoTemp.delete()
                audioTemp.delete()
                Log.d(TAG, "Fusion reussie: ${outputFile.absolutePath} (${outputFile.length() / 1024}KB)")
                callback.onProgress(100, outputFile.length(), outputFile.length())
                callback.onSuccess(outputFile)
            } else {
                Log.w(TAG, "Fusion MediaMuxer echouee, fallback video seule")
                // Fallback: essayer de livrer la video seule avec extension .mp4
                videoTemp.renameTo(outputFile)
                callback.onSuccess(outputFile)
            }

        } catch (e: Exception) {
            videoTemp.delete()
            audioTemp.delete()
            Log.e(TAG, "Erreur DASH download: ${e.message}", e)
            callback.onError("Erreur telechargement DASH: ${e.message}")
        }
    }

    private fun downloadStreamToFile(
        url: String,
        file: File,
        cookies: String?,
        onProgress: (Int) -> Unit
    ) {
        val totalBytes = getContentLength(url, cookies)
        var downloaded = 0L

        file.parentFile?.mkdirs()
        openConnection(url, cookies).use { (connection, stream) ->
            // First-byte content sniffing for DASH streams
            val firstBytes = ByteArray(16)
            val bytesRead = stream.read(firstBytes)
            if (bytesRead > 0) {
                if (!isMediaContent(firstBytes, bytesRead)) {
                    Log.e(TAG, "DASH stream: contenu non-media detecte (premiers bytes). URL expiree?")
                    throw Exception("Contenu non-media detecte dans le stream DASH. URL expiree.")
                }
            }

            FileOutputStream(file).use { fos ->
                // Write the first bytes we already read
                if (bytesRead > 0) {
                    fos.write(firstBytes, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalBytes > 0) onProgress(((downloaded * 100) / totalBytes).toInt())
                }

                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        onProgress(((downloaded * 100) / totalBytes).toInt())
                    }
                }
            }
            connection.disconnect()
        }

        // Validate downloaded DASH stream
        if (file.exists() && !validateDownloadedFile(file)) {
            file.delete()
            throw Exception("Stream DASH telecharge invalide (pas un media). URL expiree.")
        }

        Log.d(TAG, "Stream telecharge: ${file.name} (${file.length() / 1024}KB)")
    }

    /**
     * Fusionne video + audio MP4 avec MediaMuxer (natif Android).
     * Compatible MP4/M4A (H.264 + AAC). WebM/VP9 necessiterait FFmpeg.
     */
    private fun mergeWithMediaMuxer(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Boolean {
        try {
            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val videoExtractor = android.media.MediaExtractor()
            videoExtractor.setDataSource(videoFile.absolutePath)

            val audioExtractor = android.media.MediaExtractor()
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrackIndex = findAndAddTrack(muxer, videoExtractor, "video/")
            val audioTrackIndex = findAndAddTrack(muxer, audioExtractor, "audio/")

            if (videoTrackIndex < 0 || audioTrackIndex < 0) {
                Log.e(TAG, "Pistes non trouvees: video=$videoTrackIndex, audio=$audioTrackIndex")
                muxer.release()
                videoExtractor.release()
                audioExtractor.release()
                return false
            }

            muxer.start()

            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            // Copier la piste video
            val vIdx = findTrackIndex(videoExtractor, "video/")
            if (vIdx >= 0) {
                videoExtractor.selectTrack(vIdx)
                var sawEOS = false
                while (!sawEOS) {
                    bufferInfo.offset = 0
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        bufferInfo.size = 0
                        sawEOS = true
                    } else {
                        bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                }
            }

            // Copier la piste audio
            val aIdx = findTrackIndex(audioExtractor, "audio/")
            if (aIdx >= 0) {
                audioExtractor.selectTrack(aIdx)
                var sawEOS = false
                while (!sawEOS) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        bufferInfo.size = 0
                        sawEOS = true
                    } else {
                        bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                        audioExtractor.advance()
                    }
                }
            }

            muxer.stop()
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()

            return outputFile.exists() && outputFile.length() > 0

        } catch (e: Exception) {
            Log.e(TAG, "Erreur MediaMuxer: ${e.message}", e)
            return false
        }
    }

    private fun findAndAddTrack(
        muxer: MediaMuxer,
        extractor: android.media.MediaExtractor,
        mimePrefix: String
    ): Int {
        val trackIndex = findTrackIndex(extractor, mimePrefix)
        if (trackIndex < 0) return -1
        val format = extractor.getTrackFormat(trackIndex)
        return muxer.addTrack(format)
    }

    private fun findTrackIndex(extractor: android.media.MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun openConnection(
        url: String,
        cookies: String?,
        rangeStart: Long = 0L
    ): Pair<HttpURLConnection, InputStream> {
        var currentUrl = url
        var redirectCount = 0

        while (redirectCount < MAX_REDIRECTS) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = false
                buildDownloadHeaders(cookies).forEach { (k, v) ->
                    setRequestProperty(k, v)
                }
                if (rangeStart > 0) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }

            val responseCode = connection.responseCode
            // Log content-type for debugging
            val contentType = connection.getHeaderField("Content-Type") ?: ""
            Log.d(TAG, "HTTP $responseCode (Content-Type: $contentType) pour: ${currentUrl.take(100)}...")

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Validate Content-Type — reject HTML/JSON error pages
                    val ctLower = contentType.lowercase()
                    if (ctLower.contains("text/html") || ctLower.contains("application/json") ||
                        ctLower.contains("text/plain")) {
                        val errorBody = try {
                            connection.inputStream?.bufferedReader()?.readText()?.take(500)
                        } catch (_: Exception) { null }
                        connection.disconnect()
                        throw Exception("Contenu inattendu ($contentType au lieu de video/audio). " +
                            "URL probablement expiree. Rechargez la page. Detail: ${errorBody ?: "N/A"}")
                    }
                    return Pair(connection, connection.inputStream)
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    return Pair(connection, connection.inputStream)
                }
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_MOVED_PERM,
                307, 308 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw Exception("Redirection sans Location header")
                    connection.disconnect()
                    currentUrl = location
                    redirectCount++
                    Log.d(TAG, "Redirection vers: ${location.take(80)}...")
                }
                403 -> {
                    val errorBody = try {
                        connection.errorStream?.bufferedReader()?.readText()?.take(300)
                    } catch (_: Exception) { null }
                    connection.disconnect()
                    throw Exception("HTTP 403 Forbidden — URL expiree ou acces refuse. " +
                        "Rechargez la page video YouTube et reessayez. Detail: ${errorBody ?: "N/A"}")
                }
                else -> {
                    val errorBody = try {
                        connection.errorStream?.bufferedReader()?.readText()?.take(200)
                    } catch (_: Exception) { null }
                    connection.disconnect()
                    throw Exception("HTTP $responseCode inattendu. Detail: ${errorBody ?: "N/A"}")
                }
            }
        }
        throw Exception("Trop de redirections ($MAX_REDIRECTS)")
    }

    private fun getContentLength(url: String, cookies: String?): Long {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                buildDownloadHeaders(cookies).forEach { (k, v) ->
                    setRequestProperty(k, v)
                }
            }
            val length = connection.contentLengthLong
            connection.disconnect()
            length
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de determiner la taille: ${e.message}")
            -1L
        }
    }

    private fun getYouTubeCookies(): String? {
        return try {
            val cookieManager = CookieManager.getInstance()
            val ytCookies = cookieManager.getCookie("https://www.youtube.com")
            val googleCookies = cookieManager.getCookie("https://google.com")

            listOfNotNull(ytCookies, googleCookies)
                .joinToString("; ")
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de recuperer les cookies: ${e.message}")
            null
        }
    }

    private fun Pair<HttpURLConnection, InputStream>.use(
        block: (Pair<HttpURLConnection, InputStream>) -> Unit
    ) {
        try {
            block(this)
        } finally {
            try { second.close() } catch (_: Exception) {}
            try { first.disconnect() } catch (_: Exception) {}
        }
    }
}
