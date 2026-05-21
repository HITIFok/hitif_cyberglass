package com.hitif.videodownloader.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * YouTubeExtractor — Extraction des streams YouTube via l'API InnerTube.
 *
 * STRATEGIE D'EXTRACTION (DUAL CLIENT) :
 * ─────────────────────────────────────────────────────────────────────────
 * 1. WEB client (clientName=1) + sapisidhash : Quand les cookies du WebView
 *    sont disponibles (SAPISID). Meilleure compatibilite, formats 1080p+,
 *    fonctionne pour les videos age-restreintes si connecte.
 *
 * 2. ANDROID client (clientName=3) : Fallback sans cookies. Fonctionne pour
 *    les videos publiques uniquement.
 *
 * POURQUOI L'ANCIENNE APPROCHE ECHOUAIT :
 * Le client ANDROID (v19.29.37) sans SAPISIDHASH etait bloque par YouTube
 * car la version etait obsolete et trop de requetes non-authentifiees sont
 * desormais rejetees avec playabilityStatus=LOGIN_REQUIRED.
 */
class YouTubeExtractor {

    companion object {
        private const val TAG = "YouTubeExtractor"

        // API InnerTube endpoint
        private const val INNERTUBE_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

        private const val ORIGIN = "https://www.youtube.com"

        // ── WEB client (primaire — avec cookies) ──────────────────────
        private const val WEB_CLIENT_VERSION = "2.20250513.00.00"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // ── ANDROID client (fallback — sans cookies) ──────────────────
        private const val ANDROID_CLIENT_VERSION = "19.45.36"
        private const val ANDROID_UA =
            "com.google.android.youtube/$ANDROID_CLIENT_VERSION " +
            "(Linux; U; Android 14; en_US) gzip"

        /**
         * Calcule le sapisidhash pour l'authorization header.
         * Format: SAPISIDHASH <timestamp>_<SHA1(timestamp + " " + SAPISID + " " + origin)>
         */
        fun computeSapisidhash(sapisid: String, origin: String = ORIGIN): String {
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val input = "$timestamp $sapisid $origin"
            val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
            val hash = digest.joinToString("") { "%02x".format(it) }
            return "SAPISIDHASH ${timestamp}_${hash}"
        }

        /** Extrait le cookie SAPISID depuis une chaine de cookies */
        fun extractSapisid(cookies: String?): String? {
            if (cookies.isNullOrEmpty()) return null
            // Parse cookie string "name=value; name2=value2; ..."
            val cookieMap = cookies.split(";").associate { entry ->
                val parts = entry.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim()
                else parts[0].trim() to ""
            }
            return cookieMap["SAPISID"] ?: cookieMap["__Secure-3PAPISID"]
        }

        fun buildInnerTubeBody(videoId: String, useWebClient: Boolean): String {
            return if (useWebClient) """
                {
                  "videoId": "$videoId",
                  "context": {
                    "client": {
                      "clientName": "WEB",
                      "clientVersion": "$WEB_CLIENT_VERSION",
                      "hl": "en",
                      "gl": "US",
                      "userAgent": "$WEB_UA"
                    }
                  },
                  "playbackContext": {
                    "contentPlaybackContext": {
                      "html5Preference": "HTML5_PREF_WANTS",
                      "autoCaptionsDefaultOn": false,
                      "lactMilliseconds": "-1"
                    }
                  },
                  "contentCheckOk": true,
                  "racyCheckOk": true
                }
            """.trimIndent() else """
                {
                  "videoId": "$videoId",
                  "context": {
                    "client": {
                      "clientName": "ANDROID",
                      "clientVersion": "$ANDROID_CLIENT_VERSION",
                      "androidSdkVersion": 34,
                      "osName": "Android",
                      "osVersion": "14.0",
                      "hl": "en",
                      "gl": "US",
                      "utcOffsetMinutes": 0,
                      "userAgent": "$ANDROID_UA",
                      "timeZone": "UTC"
                    }
                  },
                  "playbackContext": {
                    "contentPlaybackContext": {
                      "html5Preference": "HTML5_PREF_WANTS",
                      "autoCaptionsDefaultOn": false,
                      "lactMilliseconds": "-1"
                    }
                  },
                  "contentCheckOk": true,
                  "racyCheckOk": true
                }
            """.trimIndent()
        }

        /** Extrait l'ID video depuis une URL YouTube */
        fun extractVideoId(url: String): String? {
            if (url.isBlank()) return null
            val patterns = listOf(
                Regex("""(?:v=|v/|embed/|shorts/|youtu\.be/)([a-zA-Z0-9_-]{11})"""),
                Regex("""^([a-zA-Z0-9_-]{11})$""")
            )
            for (pattern in patterns) {
                val match = pattern.find(url)
                if (match != null) return match.groupValues[1]
            }
            return null
        }

        /** Verifie si une URL est une URL YouTube page ou stream */
        fun isYouTubeUrl(url: String): Boolean {
            return url.contains("youtube.com/watch") ||
                    url.contains("youtu.be/") ||
                    url.contains("youtube.com/shorts/") ||
                    url.contains("youtube.com/embed/") ||
                    url.contains("googlevideo.com")
        }

        /** Verifie si une URL est un stream GoogleVideo (session-bound) */
        fun isGoogleVideoUrl(url: String): Boolean =
            url.contains("googlevideo.com") || url.contains("videoplayback")

        /** Verifie si l'URL est une page YouTube (pas un stream) */
        fun isYouTubePageUrl(url: String): Boolean {
            return url.contains("youtube.com/watch") ||
                    url.contains("youtu.be/") ||
                    url.contains("youtube.com/shorts/") ||
                    url.contains("youtube.com/embed/") ||
                    url.contains("m.youtube.com/watch") ||
                    url.contains("music.youtube.com/watch")
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────

    data class YouTubeStream(
        val url: String,
        val mimeType: String,
        val quality: String,
        val qualityLabel: String?,
        val width: Int,
        val height: Int,
        val contentLength: Long,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val itag: Int,
        val fps: Int = 0,
        val audioQuality: String? = null
    ) {
        val isAdaptive: Boolean get() = hasVideo != hasAudio
        val isMuxed: Boolean get() = hasVideo && hasAudio
        val fileExtension: String get() = when {
            mimeType.contains("mp4") -> "mp4"
            mimeType.contains("webm") -> "webm"
            mimeType.contains("audio/mp4") -> "m4a"
            else -> "mp4"
        }
    }

    data class ExtractionResult(
        val videoId: String,
        val title: String,
        val duration: Long,
        val thumbnail: String,
        val muxedFormats: List<YouTubeStream>,
        val videoOnlyFormats: List<YouTubeStream>,
        val audioOnlyFormats: List<YouTubeStream>,
        val hlsManifestUrl: String?
    ) {
        /** Meilleur format combine (pas de fusion necessaire) */
        fun bestMuxedFormat(): YouTubeStream? =
            muxedFormats.sortedByDescending { it.height }.firstOrNull()

        /** Meilleure paire video + audio pour DASH */
        fun bestAdaptivePair(maxHeight: Int = 1080): Pair<YouTubeStream, YouTubeStream>? {
            val video = videoOnlyFormats
                .filter { it.height <= maxHeight && it.mimeType.contains("mp4") }
                .sortedByDescending { it.height }
                .firstOrNull() ?: return null
            val audio = audioOnlyFormats
                .filter { it.mimeType.contains("mp4") || it.mimeType.contains("m4a") }
                .sortedByDescending { it.contentLength }
                .firstOrNull() ?: return null
            return Pair(video, audio)
        }

        fun selectBestForDownload(preferHighQuality: Boolean = false): DownloadStrategy {
            if (!preferHighQuality) {
                val muxed = bestMuxedFormat()
                if (muxed != null) return DownloadStrategy.Muxed(muxed)
            }
            val pair = bestAdaptivePair()
            if (pair != null) return DownloadStrategy.Adaptive(pair.first, pair.second)
            val fallback = bestMuxedFormat()
            if (fallback != null) return DownloadStrategy.Muxed(fallback)
            if (hlsManifestUrl != null) return DownloadStrategy.Hls(hlsManifestUrl)
            return DownloadStrategy.Error("Aucun format disponible")
        }
    }

    sealed class DownloadStrategy {
        data class Muxed(val stream: YouTubeStream) : DownloadStrategy()
        data class Adaptive(val video: YouTubeStream, val audio: YouTubeStream) : DownloadStrategy()
        data class Hls(val manifestUrl: String) : DownloadStrategy()
        data class Error(val message: String) : DownloadStrategy()
    }

    // ── Extraction API ───────────────────────────────────────────────────

    /**
     * Extrait les streams pour une URL YouTube.
     * @param youtubeUrl URL YouTube ou googlevideo
     * @param pageUrl URL de la page courante (pour extraction videoId)
     * @param cookies Cookies du WebView (pour sapisidhash auth)
     */
    suspend fun extract(
        youtubeUrl: String,
        pageUrl: String? = null,
        cookies: String? = null
    ): ExtractionResult? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: extractVideoId(pageUrl ?: "")
            ?: run {
                Log.e(TAG, "Impossible d'extraire l'ID video depuis: $youtubeUrl (pageUrl=$pageUrl)")
                return@withContext null
            }
        Log.d(TAG, "Extraction InnerTube pour videoId: $videoId")

        val sapisid = extractSapisid(cookies)

        if (sapisid != null) {
            Log.d(TAG, "SAPISID found — using WEB client with sapisidhash")
            callInnerTubeApi(videoId, useWebClient = true, sapisid = sapisid)
        } else {
            Log.d(TAG, "No SAPISID cookie — falling back to ANDROID client")
            // Try ANDROID client first (no auth needed for public videos)
            val result = callInnerTubeApi(videoId, useWebClient = false, sapisid = null)
            if (result == null) {
                // Retry with WEB client even without SAPISID (some public videos work)
                Log.d(TAG, "ANDROID client failed — retrying with WEB client (no auth)")
                callInnerTubeApi(videoId, useWebClient = true, sapisid = null)
            } else {
                result
            }
        }
    }

    suspend fun extractByVideoId(videoId: String): ExtractionResult? =
        withContext(Dispatchers.IO) {
            callInnerTubeApi(videoId, useWebClient = false, sapisid = null)
        }

    private fun callInnerTubeApi(
        videoId: String,
        useWebClient: Boolean,
        sapisid: String?
    ): ExtractionResult? {
        val connection: HttpURLConnection
        try {
            connection = URL(INNERTUBE_URL).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.e(TAG, "Erreur connexion InnerTube: ${e.message}")
            return null
        }

        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                instanceFollowRedirects = false
            }

            // ── Headers ──────────────────────────────────────────────
            if (useWebClient) {
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("User-Agent", WEB_UA)
                connection.setRequestProperty("X-YouTube-Client-Name", "1")
                connection.setRequestProperty("X-YouTube-Client-Version", WEB_CLIENT_VERSION)
                connection.setRequestProperty("X-Goog-Api-Format-Version", "1")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connection.setRequestProperty("Origin", ORIGIN)
                connection.setRequestProperty("Referer", "$ORIGIN/")

                // Authorization avec sapisidhash
                if (sapisid != null) {
                    val authHeader = computeSapisidhash(sapisid)
                    connection.setRequestProperty("Authorization", authHeader)
                    connection.setRequestProperty("X-Goog-AuthUser", "0")
                    Log.d(TAG, "Authorization: $authHeader")
                }
            } else {
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("User-Agent", ANDROID_UA)
                connection.setRequestProperty("X-YouTube-Client-Name", "3")
                connection.setRequestProperty("X-YouTube-Client-Version", ANDROID_CLIENT_VERSION)
                connection.setRequestProperty("X-Goog-Api-Format-Version", "1")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connection.setRequestProperty("Origin", ORIGIN)
                connection.setRequestProperty("Referer", "$ORIGIN/")
            }

            // ── Send request ─────────────────────────────────────────
            val body = buildInnerTubeBody(videoId, useWebClient).toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }

            // ── Read response ────────────────────────────────────────
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "InnerTube HTTP $responseCode (${if (useWebClient) "WEB" else "ANDROID"}) " +
                    "pour videoId=$videoId: ${errorBody?.take(500)}")
                connection.disconnect()
                return null
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            return parseInnerTubeResponse(videoId, responseText, useWebClient)

        } catch (e: Exception) {
            Log.e(TAG, "Erreur InnerTube API (${if (useWebClient) "WEB" else "ANDROID"}): ${e.message}", e)
            connection.disconnect()
            return null
        }
    }

    private fun parseInnerTubeResponse(
        videoId: String,
        json: String,
        useWebClient: Boolean
    ): ExtractionResult? {
        try {
            val root = JSONObject(json)

            // Verifier le statut de lecture
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status")
            if (status == "ERROR") {
                val reason = playabilityStatus?.optString("reason") ?: "Inconnu"
                Log.e(TAG, "Video ERROR ($videoId): $reason")
                return null
            }
            if (status == "LOGIN_REQUIRED") {
                val messages = playabilityStatus?.optJSONArray("messages")
                val msg = if (messages != null && messages.length() > 0) messages.getString(0) else "N/A"
                Log.e(TAG, "Video LOGIN_REQUIRED ($videoId): $msg " +
                    "[client=${if (useWebClient) "WEB" else "ANDROID"}]")
                return null
            }
            // Log non-OK status for debugging
            if (status != null && status != "OK") {
                val reason = playabilityStatus?.optString("reason") ?: ""
                Log.w(TAG, "Playability status=$status reason=$reason ($videoId)")
            }

            // Metadonnees
            val videoDetails = root.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title") ?: "YouTube_$videoId"
            val durationSeconds = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
            val thumbnails = videoDetails?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
            val thumbnail = if (thumbnails != null && thumbnails.length() > 0)
                thumbnails.getJSONObject(thumbnails.length() - 1).optString("url") ?: ""
            else ""

            // Streaming data — CRITICAL
            val streamingData = root.optJSONObject("streamingData")
            if (streamingData == null) {
                // Video might be a live stream or otherwise restricted
                val isLive = videoDetails?.optBoolean("isLive", false) ?: false
                val isLiveContent = videoDetails?.optBoolean("isLiveContent", false) ?: false
                if (isLive || isLiveContent) {
                    Log.w(TAG, "Video $videoId est un live — pas de streamingData")
                } else {
                    Log.e(TAG, "Pas de streamingData pour $videoId. " +
                        "playability=$status, title='$title'")
                }
                return null
            }

            val hlsManifestUrl = streamingData.optString("hlsManifestUrl")
                .takeIf { it.isNotEmpty() }

            val muxedFormats = mutableListOf<YouTubeStream>()
            val videoOnlyFormats = mutableListOf<YouTubeStream>()
            val audioOnlyFormats = mutableListOf<YouTubeStream>()

            // formats = muxed (video+audio combined, typically max 720p)
            streamingData.optJSONArray("formats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    parseFormat(arr.getJSONObject(i), hasVideo = true, hasAudio = true)
                        ?.let { muxedFormats.add(it) }
                }
            }

            // adaptiveFormats = DASH (video-only OR audio-only)
            streamingData.optJSONArray("adaptiveFormats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val mime = obj.optString("mimeType")
                    when {
                        mime.startsWith("video/") -> {
                            parseFormat(obj, hasVideo = true, hasAudio = false)
                                ?.let { videoOnlyFormats.add(it) }
                        }
                        mime.startsWith("audio/") -> {
                            parseFormat(obj, hasVideo = false, hasAudio = true)
                                ?.let { audioOnlyFormats.add(it) }
                        }
                    }
                }
            }

            val totalFormats = muxedFormats.size + videoOnlyFormats.size + audioOnlyFormats.size
            Log.d(TAG,
                "Extraction OK ($videoId): '$title' | " +
                "muxed=${muxedFormats.size} video=${videoOnlyFormats.size} " +
                "audio=${audioOnlyFormats.size} total=$totalFormats" +
                if (hlsManifestUrl != null) " +HLS" else ""
            )

            return ExtractionResult(
                videoId = videoId,
                title = title,
                duration = durationSeconds,
                thumbnail = thumbnail,
                muxedFormats = muxedFormats.sortedByDescending { it.height },
                videoOnlyFormats = videoOnlyFormats.sortedByDescending { it.height },
                audioOnlyFormats = audioOnlyFormats.sortedByDescending { it.contentLength },
                hlsManifestUrl = hlsManifestUrl
            )

        } catch (e: Exception) {
            Log.e(TAG, "Erreur parsing reponse InnerTube ($videoId): ${e.message}", e)
            return null
        }
    }

    private fun parseFormat(obj: JSONObject, hasVideo: Boolean, hasAudio: Boolean): YouTubeStream? {
        try {
            val url = obj.optString("url").takeIf { it.isNotEmpty() }
                ?: decodeCipher(
                    obj.optString("signatureCipher").takeIf { it.isNotEmpty() }
                        ?: obj.optString("cipher").takeIf { it.isNotEmpty() }
                        ?: return null
                )
                ?: return null

            val mimeType = obj.optString("mimeType").substringBefore(";").trim()
            val itag = obj.optInt("itag", 0)
            val qualityLabel = obj.optString("qualityLabel").takeIf { it.isNotEmpty() }
            val quality = obj.optString("quality")
            val width = obj.optInt("width", 0)
            val height = obj.optInt("height", 0)
            val fps = obj.optInt("fps", 0)
            val audioQuality = obj.optString("audioQuality").takeIf { it.isNotEmpty() }
            val contentLength = obj.optString("contentLength").toLongOrNull()
                ?: obj.optLong("contentLength", 0L)

            return YouTubeStream(
                url = url, mimeType = mimeType, quality = quality,
                qualityLabel = qualityLabel ?: resolveQualityLabel(height),
                width = width, height = height, contentLength = contentLength,
                hasVideo = hasVideo, hasAudio = hasAudio,
                itag = itag, fps = fps, audioQuality = audioQuality
            )
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de parser le format itag=${obj.optInt("itag")}: ${e.message}")
            return null
        }
    }

    /**
     * Decode signatureCipher / cipher parameter into a download URL.
     *
     * For the WEB client, formats may have signatureCipher instead of direct URL.
     * The cipher contains: url, s (obfuscated signature), sp (sig param name), n (nsig).
     *
     * NOTE: The 's' parameter is an obfuscated signature that normally needs to be
     * decoded using the YouTube player's JavaScript signature function. However,
     * when using the WEB client with valid sapisidhash, the InnerTube API often
     * returns direct URLs (no cipher needed) or cipher with pre-decoded 'sig'.
     * If extraction still fails, full nsig deobfuscation would be needed.
     */
    private fun decodeCipher(cipher: String?): String? {
        if (cipher == null) return null
        try {
            val params = cipher.split("&").associate { param ->
                val idx = param.indexOf('=')
                if (idx < 0) param to ""
                else param.substring(0, idx) to URLDecoder.decode(param.substring(idx + 1), "UTF-8")
            }
            val baseUrl = params["url"] ?: return null

            // Build the decoded URL with all relevant parameters
            val decodedUrl = StringBuilder(baseUrl)

            // Signature parameter (s/sig -> sp)
            val sig = params["sig"] ?: params["s"]
            val sp = params["sp"] ?: "signature"
            if (sig != null) {
                decodedUrl.append("&").append(sp).append("=").append(sig)
            }

            // nsig parameter (n) — YouTube throttling signature
            // Required to avoid download speed throttling or 403 errors
            val nsig = params["n"]
            if (nsig != null) {
                decodedUrl.append("&n=").append(nsig)
            }

            // Digital nonce
            val dn = params["dn"]
            if (dn != null) {
                decodedUrl.append("&dn=").append(dn)
            }

            val result = decodedUrl.toString()
            Log.d(TAG, "decodeCipher: URL length=${result.length}, " +
                "has_sig=${sig != null}, has_nsig=${nsig != null}")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de decoder le cipher: ${e.message}")
            return null
        }
    }

    private fun resolveQualityLabel(height: Int): String = when {
        height >= 2160 -> "4K (2160p)"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p HD"
        height >= 720  -> "720p HD"
        height >= 480  -> "480p"
        height >= 360  -> "360p"
        height >= 240  -> "240p"
        height > 0     -> "${height}p"
        else           -> "Audio"
    }
}
