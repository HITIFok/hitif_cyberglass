package com.hitif.videodownloader.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * YouTubeExtractor — Extraction des streams YouTube via l'API InnerTube.
 *
 * POURQUOI LE PROBLEME 403 EXISTAIT :
 * ─────────────────────────────────────────────────────────────────────────
 * L'app interceptait les URLs googlevideo.com capturées par shouldInterceptRequest
 * ou le bridge JS. Ces URLs sont liees a la session/IP du WebView (parametre "ip="),
 * signees avec un token de session (parametre "sig="), et protegees par le
 * parametre "n" obfusque (throttling).
 *
 * Quand TurboDownloadEngine essayait de telecharger cette URL :
 *   - Sans les cookies YouTube valides
 *   - Sans le bon User-Agent
 *   - Parfois depuis une IP differente
 * → YouTube repondait 403 Forbidden.
 *
 * SOLUTION :
 * ─────────────────────────────────────────────────────────────────────────
 * Au lieu d'utiliser l'URL interceptee (liee a la session WebView), on appelle
 * directement l'API InnerTube de YouTube avec le client ANDROID.
 *
 * Le client ANDROID retourne des URLs "signees differemment" :
 *   - Pas de parametre "n" a decoder
 *   - Pas de signatureCipher a dechiffrer
 *   - URLs directement telechargeables avec User-Agent Android
 */
class YouTubeExtractor {

    companion object {
        private const val TAG = "YouTubeExtractor"

        // API InnerTube — client ANDROID
        private const val INNERTUBE_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

        // User-Agent du client officiel YouTube Android
        private const val YT_ANDROID_UA =
            "com.google.android.youtube/19.29.37 (Linux; U; Android 11; en_US) gzip"

        private val INNERTUBE_HEADERS = mapOf(
            "Content-Type"              to "application/json; charset=UTF-8",
            "User-Agent"                to YT_ANDROID_UA,
            "X-YouTube-Client-Name"     to "3",
            "X-YouTube-Client-Version"  to "19.29.37",
            "X-Goog-Api-Format-Version" to "1",
            "Accept-Language"           to "en-US,en;q=0.9",
            "Origin"                    to "https://www.youtube.com",
            "Referer"                   to "https://www.youtube.com/"
        )

        private fun buildInnerTubeBody(videoId: String): String = """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  "clientName": "ANDROID",
                  "clientVersion": "19.29.37",
                  "androidSdkVersion": 30,
                  "osName": "Android",
                  "osVersion": "11.0",
                  "hl": "en",
                  "gl": "US",
                  "utcOffsetMinutes": 0,
                  "userAgent": "$YT_ANDROID_UA",
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

        /** Extrait l'ID video depuis une URL YouTube */
        fun extractVideoId(url: String): String? {
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
     * @param pageUrl URL de la page courante (pour Referer et extraction videoId)
     */
    suspend fun extract(youtubeUrl: String, pageUrl: String? = null): ExtractionResult? =
        withContext(Dispatchers.IO) {
            val videoId = extractVideoId(youtubeUrl)
                ?: extractVideoId(pageUrl ?: "")
                ?: run {
                    Log.e(TAG, "Impossible d'extraire l'ID video depuis: $youtubeUrl")
                    return@withContext null
                }
            Log.d(TAG, "Extraction InnerTube pour videoId: $videoId")
            callInnerTubeApi(videoId)
        }

    suspend fun extractByVideoId(videoId: String): ExtractionResult? =
        withContext(Dispatchers.IO) { callInnerTubeApi(videoId) }

    private fun callInnerTubeApi(videoId: String): ExtractionResult? {
        try {
            val connection = URL(INNERTUBE_URL).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                INNERTUBE_HEADERS.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val body = buildInnerTubeBody(videoId).toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "InnerTube API erreur HTTP $responseCode pour videoId=$videoId: ${errorBody?.take(300)}")
                connection.disconnect()
                return null
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            return parseInnerTubeResponse(videoId, responseText)

        } catch (e: Exception) {
            Log.e(TAG, "Erreur InnerTube API: ${e.message}", e)
            return null
        }
    }

    private fun parseInnerTubeResponse(videoId: String, json: String): ExtractionResult? {
        try {
            val root = JSONObject(json)

            // Verifier le statut
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status")
            if (status == "ERROR" || status == "LOGIN_REQUIRED") {
                val reason = playabilityStatus?.optString("reason") ?: "Inconnu"
                Log.e(TAG, "Video non disponible: $status — $reason")
                return null
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

            // Streaming data
            val streamingData = root.optJSONObject("streamingData") ?: run {
                Log.e(TAG, "Pas de streamingData dans la reponse InnerTube")
                return null
            }

            val hlsManifestUrl = streamingData.optString("hlsManifestUrl")
                .takeIf { it.isNotEmpty() }

            val muxedFormats = mutableListOf<YouTubeStream>()
            val videoOnlyFormats = mutableListOf<YouTubeStream>()
            val audioOnlyFormats = mutableListOf<YouTubeStream>()

            // formats = muxes (video+audio, max 720p)
            streamingData.optJSONArray("formats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    parseFormat(arr.getJSONObject(i), hasVideo = true, hasAudio = true)
                        ?.let { muxedFormats.add(it) }
                }
            }

            // adaptiveFormats = DASH (video seul OU audio seul)
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

            Log.d(TAG,
                "Extraction reussie: $title | " +
                "muxed=${muxedFormats.size} | " +
                "video=${videoOnlyFormats.size} | " +
                "audio=${audioOnlyFormats.size}"
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
            Log.e(TAG, "Erreur parsing reponse InnerTube: ${e.message}", e)
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
            Log.w(TAG, "Impossible de parser le format: ${e.message}")
            return null
        }
    }

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

            // Also include any other known useful parameters
            val dn = params["dn"] // digital nonce
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
