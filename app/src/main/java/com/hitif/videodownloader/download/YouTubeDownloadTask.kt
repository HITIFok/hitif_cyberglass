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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * YouTubeDownloadTask — Telechargement robuste des videos YouTube via OkHttp.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * CORRECTION DEFINITIVE V3 (403 Forbidden) :
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * CAUSE RACINE DU 403 :
 * L'ancienne version utilisait java.net.HttpURLConnection pour le telechargement.
 * HttpURLConnection (Android) est un vieux wrapper autour d'une ancienne version
 * interne d'OkHttp, avec un fingerprint TLS/HTTP2 different d'OkHttp moderne.
 * googlevideo.com detecte cette difference entre l'extraction (OkHttp moderne)
 * et le telechargement (HttpURLConnection) → rejet 403.
 *
 * CORRECTION :
 * - Remplacement TOTAL de HttpURLConnection par OkHttp pour le telechargement.
 * - cleanDownloadClient (sans cookies) pour TV_EMBEDDED/IOS/ANDROID.
 * - sessionDownloadClient (avec WebViewCookieJar) pour WEB uniquement.
 * - Meme stack HTTP entre extraction et telechargement → meme fingerprint TLS.
 *
 * Autres corrections maintenues (V2) :
 * - PAS de requete HEAD avant le telechargement.
 * - Cookies conditionnels (uniquement si extraction avec sessionClient).
 * - Headers adaptes au client (Sec-Fetch-* uniquement avec UA navigateur).
 * - User-Agent identique entre extraction et telechargement.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class YouTubeDownloadTask {

    companion object {
        private const val TAG = "YTDownloadTask"
        private const val BUFFER_SIZE = 128 * 1024

        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L

        // ── Clients OkHttp pour le telechargement ────────────────────────────
        // Meme stack HTTP que YouTubeExtractor → fingerprint TLS identique.

        /** Client SANS cookies — pour les streams extraits par TV_EMBEDDED/IOS/ANDROID.
         *  CRITIQUE : ne JAMAIS envoyer de cookies avec ces streams. */
        private val cleanDownloadClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        /** Client AVEC cookies — pour les streams extraits par WEB (sessionClient).
         *  Utilise WebViewCookieJar pour transmettre les cookies YouTube. */
        private val sessionDownloadClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .cookieJar(WebViewCookieJar())
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        /** Detecte si un UA est un UA navigateur (Chrome) ou une app native */
        private fun isBrowserUserAgent(ua: String): Boolean =
            ua.contains("Mozilla/") || ua.contains("Gecko/") || ua.contains("Chrome/")

        /**
         * Construit un OkHttp Request.Builder avec les headers adaptes au client.
         *
         * - UA navigateur (WEB/TV_EMBEDDED) → headers complets avec Sec-Fetch-*
         * - UA app native (IOS/ANDROID) → headers minimaux
         *
         * @param sendCookies true uniquement si sessionDownloadClient est utilise (WEB).
         */
        private fun buildDownloadRequest(
            url: String,
            userAgent: String?,
            sendCookies: Boolean = false,
            rangeStart: Long = 0L
        ): Request.Builder {
            val ua = userAgent
                ?: "com.google.android.youtube/19.29.37 (Linux; U; Android 11; en_US) gzip"
            val isBrowser = isBrowserUserAgent(ua)

            val builder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "identity")
                .header("Connection", "keep-alive")

            // Headers navigateur uniquement avec un UA Chrome
            if (isBrowser) {
                builder.header("Origin", "https://www.youtube.com")
                builder.header("Referer", "https://www.youtube.com/")
                builder.header("Sec-Fetch-Dest", "video")
                builder.header("Sec-Fetch-Mode", "no-cors")
                builder.header("Sec-Fetch-Site", "cross-site")
            }

            // Range header pour la reprise
            if (rangeStart > 0) {
                builder.header("Range", "bytes=$rangeStart-")
            }

            // NE PAS mettre de header Cookie manuellement quand on utilise
            // sessionDownloadClient — WebViewCookieJar s'en charge via OkHttp.
            // Et avec cleanDownloadClient, on ne veut AUCUN cookie.

            return builder
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
            if (fileSize < 10_240) {
                Log.w(TAG, "Validation: file suspiciously small (${fileSize}B) — checking content")
                return isMediaContent(file.readBytes(), fileSize.toInt())
            }
            try {
                val header = ByteArray(16)
                file.inputStream().use { input ->
                    val read = input.read(header)
                    return isMediaContent(header, read)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Validation: read error: ${e.message}")
                return true
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
     * Telecharge une video YouTube avec la strategie optimale via OkHttp.
     */
    suspend fun download(
        context: Context,
        result: YouTubeExtractor.ExtractionResult,
        outputDir: File,
        fileName: String,
        preferHighQuality: Boolean = false,
        userAgent: String? = null,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        // Choisir le client OkHttp : session uniquement pour WEB, clean pour les autres
        val useSession = result.usedSessionClient
        Log.d(TAG, "Client extraction: ${result.extractedWithClient} | " +
            "Session download: $useSession")

        when (val strategy = result.selectBestForDownload(preferHighQuality)) {
            is YouTubeExtractor.DownloadStrategy.Muxed -> {
                Log.d(TAG, "Strategie: MUXED — ${strategy.stream.qualityLabel} (${strategy.stream.fileExtension})")
                val file = File(outputDir, "$fileName.${strategy.stream.fileExtension}")
                downloadSingleStream(
                    httpClient = if (useSession) sessionDownloadClient else cleanDownloadClient,
                    url = strategy.stream.url,
                    outputFile = file,
                    userAgent = strategy.stream.userAgent,
                    sendCookies = useSession,
                    knownContentLength = strategy.stream.contentLength,
                    callback = callback
                )
            }

            is YouTubeExtractor.DownloadStrategy.Adaptive -> {
                Log.d(TAG, "Strategie: DASH — video=${strategy.video.qualityLabel} + audio")
                downloadAndMergeDash(
                    httpClient = if (useSession) sessionDownloadClient else cleanDownloadClient,
                    video = strategy.video,
                    audio = strategy.audio,
                    outputDir = outputDir,
                    fileName = fileName,
                    sendCookies = useSession,
                    callback = callback
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

    // ── Download single stream (muxed) ─────────────────────────────────────

    private suspend fun downloadSingleStream(
        httpClient: OkHttpClient,
        url: String,
        outputFile: File,
        userAgent: String?,
        sendCookies: Boolean,
        knownContentLength: Long = -1L,
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

                var downloadedBytes = 0L
                var totalBytes = knownContentLength

                // Reprise si fichier temporaire existant (tentative 1 uniquement)
                val startByte = if (tempFile.exists() && attempt == 1) tempFile.length() else 0L
                if (startByte > 0 && totalBytes > 0 && startByte < totalBytes) {
                    Log.d(TAG, "Reprise du telechargement depuis $startByte bytes")
                } else if (startByte > 0) {
                    tempFile.delete()
                }

                // Construire et executer la requete OkHttp
                val request = buildDownloadRequest(url, userAgent, sendCookies, rangeStart = startByte)
                    .build()

                val response = httpClient.newCall(request).execute()
                val code = response.code
                val contentType = response.body?.contentType()?.toString() ?: ""
                val contentLengthFromServer = response.body?.contentLength() ?: -1L

                Log.d(TAG, "OkHttp HTTP $code (Content-Type: $contentType, CL: $contentLengthFromServer) pour: ${url.take(100)}...")

                when {
                    code == 200 || code == 206 -> {
                        // OK
                    }
                    code == 403 -> {
                        val errorBody = response.body?.string()?.take(300)
                        response.close()
                        throw Exception("HTTP 403 Forbidden — URL expiree ou acces refuse. " +
                            "Rechargez la page video YouTube et reessayez. Detail: $errorBody")
                    }
                    code in 300..399 -> {
                        val errorBody = response.body?.string()?.take(200)
                        response.close()
                        throw Exception("Redirection inattendue HTTP $code. Detail: $errorBody")
                    }
                    else -> {
                        val errorBody = response.body?.string()?.take(200)
                        response.close()
                        throw Exception("HTTP $code inattendu. Detail: $errorBody")
                    }
                }

                // Calculer la taille totale
                if (contentLengthFromServer > 0) {
                    totalBytes = if (code == 206 && startByte > 0) {
                        startByte + contentLengthFromServer
                    } else {
                        contentLengthFromServer
                    }
                }

                Log.d(TAG, "Telechargement: totalBytes=$totalBytes startByte=$startByte code=$code")

                // Valider le Content-Type
                val ctLower = contentType.lowercase()
                if (ctLower.contains("text/html") || ctLower.contains("application/json") ||
                    ctLower.contains("text/plain")) {
                    val errorBody = response.body?.string()?.take(500)
                    response.close()
                    throw Exception("Contenu inattendu ($contentType au lieu de video/audio). " +
                        "URL probablement expiree. Rechargez la page. Detail: $errorBody")
                }

                // Lire le body
                val body = response.body ?: throw Exception("Response body null")
                val inputStream = body.byteStream()

                try {
                    // First-byte content sniffing
                    val firstBytes = ByteArray(16)
                    val bytesRead = inputStream.read(firstBytes)
                    if (bytesRead > 0) {
                        if (!isMediaContent(firstBytes, bytesRead)) {
                            val errorMsg = buildString {
                                append("Le serveur retourne du contenu non-media (probablement HTML/JSON erreur). ")
                                append("URL YouTube probablement expiree. Rechargez la page video et reessayez.")
                            }
                            Log.e(TAG, errorMsg)
                            lastError = errorMsg
                            tempFile.delete()
                            continue
                        }
                        FileOutputStream(tempFile, startByte > 0).use { fos ->
                            fos.write(firstBytes, 0, bytesRead)
                            downloadedBytes = startByte + bytesRead

                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
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
                        // Empty first read — continue reading normally
                        FileOutputStream(tempFile, startByte > 0).use { fos ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
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
                } finally {
                    try { inputStream.close() } catch (_: Exception) {}
                    response.close()
                }

                // Post-download validation
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
                    return@withContext
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

    // ── DASH download + merge ──────────────────────────────────────────────

    private suspend fun downloadAndMergeDash(
        httpClient: OkHttpClient,
        video: YouTubeExtractor.YouTubeStream,
        audio: YouTubeExtractor.YouTubeStream,
        outputDir: File,
        fileName: String,
        sendCookies: Boolean,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        outputDir.mkdirs()

        val videoTemp = File(outputDir, "${fileName}_video.tmp")
        val audioTemp = File(outputDir, "${fileName}_audio.tmp")
        val outputFile = File(outputDir, "$fileName.mp4")

        try {
            // Etape 1/3: Telecharger la video (0-60%)
            Log.d(TAG, "DASH Step 1/3: Telechargement video (${video.qualityLabel})")
            downloadStreamToFile(
                httpClient = httpClient,
                url = video.url,
                file = videoTemp,
                userAgent = video.userAgent,
                sendCookies = sendCookies,
                knownContentLength = video.contentLength
            ) { percent ->
                callback.onProgress((percent * 0.6).toInt(), 0, 0)
            }

            // Etape 2/3: Telecharger l'audio (60-90%)
            Log.d(TAG, "DASH Step 2/3: Telechargement audio (${audio.qualityLabel})")
            downloadStreamToFile(
                httpClient = httpClient,
                url = audio.url,
                file = audioTemp,
                userAgent = audio.userAgent,
                sendCookies = sendCookies,
                knownContentLength = audio.contentLength
            ) { percent ->
                callback.onProgress(60 + (percent * 0.3).toInt(), 0, 0)
            }

            // Etape 3/3: Fusion MediaMuxer (90-100%)
            Log.d(TAG, "DASH Step 3/3: Fusion audio+video -> $outputFile")
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

    /**
     * Telecharge un stream unique vers un fichier (DASH video/audio).
     * Utilise OkHttp — PAS de requete HEAD prealable.
     */
    private fun downloadStreamToFile(
        httpClient: OkHttpClient,
        url: String,
        file: File,
        userAgent: String?,
        sendCookies: Boolean,
        knownContentLength: Long = -1L,
        onProgress: (Int) -> Unit
    ) {
        file.parentFile?.mkdirs()
        var downloaded = 0L
        var totalBytes = knownContentLength

        val request = buildDownloadRequest(url, userAgent, sendCookies).build()
        val response = httpClient.newCall(request).execute()

        val code = response.code
        if (code == 403) {
            val errorBody = response.body?.string()?.take(300)
            response.close()
            throw Exception("HTTP 403 Forbidden sur stream DASH. Detail: $errorBody")
        }
        if (code !in 200..299) {
            val errorBody = response.body?.string()?.take(200)
            response.close()
            throw Exception("HTTP $code inattendu sur stream DASH. Detail: $errorBody")
        }

        val contentLengthFromServer = response.body?.contentLength() ?: -1L
        if (contentLengthFromServer > 0) {
            totalBytes = contentLengthFromServer
        }

        Log.d(TAG, "DASH stream: HTTP $code CL=$totalBytes pour: ${url.take(80)}...")

        val body = response.body ?: throw Exception("Response body null (DASH)")
        val inputStream = body.byteStream()

        try {
            // First-byte content sniffing
            val firstBytes = ByteArray(16)
            val bytesRead = inputStream.read(firstBytes)
            if (bytesRead > 0) {
                if (!isMediaContent(firstBytes, bytesRead)) {
                    Log.e(TAG, "DASH stream: contenu non-media detecte (premiers bytes). URL expiree?")
                    throw Exception("Contenu non-media detecte dans le stream DASH. URL expiree.")
                }
            }

            FileOutputStream(file).use { fos ->
                if (bytesRead > 0) {
                    fos.write(firstBytes, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalBytes > 0) onProgress(((downloaded * 100) / totalBytes).toInt())
                }

                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        onProgress(((downloaded * 100) / totalBytes).toInt())
                    }
                }
            }
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
            response.close()
        }

        // Validate downloaded DASH stream
        if (file.exists() && !validateDownloadedFile(file)) {
            file.delete()
            throw Exception("Stream DASH telecharge invalide (pas un media). URL expiree.")
        }

        Log.d(TAG, "Stream telecharge: ${file.name} (${file.length() / 1024}KB)")
    }

    // ── MediaMuxer merge ──────────────────────────────────────────────────

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
}
