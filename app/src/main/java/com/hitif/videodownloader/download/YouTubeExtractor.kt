package com.hitif.videodownloader.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * YouTubeExtractor — Extraction des streams YouTube via l'API InnerTube.
 *
 * utilise OkHttp au lieu de HttpURLConnection pour:
 * - Meilleure empreinte TLS (non detectable comme bot par YouTube)
 * - Forwarding automatique des cookies via WebViewCookieJar
 * - Gestion correcte des redirections
 * - Connection pooling
 *
 * STRATEGIE MULTI-CLIENT (ordre de priorite):
 * 1. TV_EMBEDDED  (clientId=85) — Pas de PO token requis. Le plus fiable.
 * 2. IOS          (clientId=5)  — Fallback. Pas de PO token. URLs directes.
 * 3. ANDROID      (clientId=3)  — Fallback. Peut echouer si PO token requis.
 * 4. WEB + sapisidhash — Si cookies SAPISID disponibles.
 */
class YouTubeExtractor {

    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val INNERTUBE_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private const val ORIGIN = "https://www.youtube.com"

        // ── Client TV Embedded (PRIMAIRE — pas de PO token) ─────────────────
        // Version mise a jour (l'ancienne 7.20231219 est depreciee par YouTube)
        private const val TV_EMBED_VERSION = "7.20250409"

        // ── Client iOS (SECONDAIRE — pas de PO token) ────────────────────────
        private const val IOS_VERSION = "20.16.7"
        private const val IOS_DEVICE  = "iPhone16,2"
        private const val IOS_UA      =
            "com.google.ios.youtube/$IOS_VERSION (iPhone16,2; U; CPU iOS 18_5_0 like Mac OS X)"

        // ── Client ANDROID (FALLBACK) ─────────────────────────────────────────
        private const val ANDROID_VERSION = "20.16.7"
        private const val ANDROID_UA      =
            "com.google.android.youtube/$ANDROID_VERSION " +
            "(Linux; U; Android 15; en_US) gzip"

        // ── Client WEB (DERNIER RECOURS avec sapisidhash) ─────────────────────
        private const val WEB_VERSION = "2.20250513.00.00"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        // ── Enum des clients disponibles ──────────────────────────────────────
        enum class Client { TV_EMBEDDED, IOS, ANDROID, WEB }

        // ── OkHttp client (singleton) ─────────────────────────────────────────
        // Utilise WebViewCookieJar pour forwarder les cookies du WebView
        // vers les requetes InnerTube API.
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .cookieJar(WebViewCookieJar())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        // ── Corps de requete InnerTube par client ─────────────────────────────

        private fun buildBody(videoId: String, client: Client, sapisid: String? = null): String =
            when (client) {
                Client.TV_EMBEDDED -> """
                    {
                      "videoId": "$videoId",
                      "context": {
                        "client": {
                          "clientName": "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                          "clientVersion": "$TV_EMBED_VERSION",
                          "clientScreen": "EMBED",
                          "hl": "en",
                          "gl": "US",
                          "utcOffsetMinutes": 0
                        },
                        "thirdParty": {
                          "embedUrl": "https://www.youtube.com/embed/$videoId"
                        }
                      },
                      "contentCheckOk": true,
                      "racyCheckOk": true
                    }
                """.trimIndent()

                Client.IOS -> """
                    {
                      "videoId": "$videoId",
                      "context": {
                        "client": {
                          "clientName": "IOS",
                          "clientVersion": "$IOS_VERSION",
                          "deviceModel": "$IOS_DEVICE",
                          "osName": "iPhone",
                          "osVersion": "18.5.0",
                          "hl": "en",
                          "gl": "US",
                          "utcOffsetMinutes": 0,
                          "userAgent": "$IOS_UA"
                        }
                      },
                      "contentCheckOk": true,
                      "racyCheckOk": true
                    }
                """.trimIndent()

                Client.ANDROID -> """
                    {
                      "videoId": "$videoId",
                      "context": {
                        "client": {
                          "clientName": "ANDROID",
                          "clientVersion": "$ANDROID_VERSION",
                          "androidSdkVersion": 35,
                          "osName": "Android",
                          "osVersion": "15",
                          "hl": "en",
                          "gl": "US",
                          "utcOffsetMinutes": 0,
                          "userAgent": "$ANDROID_UA",
                          "timeZone": "UTC"
                        }
                      },
                      "contentCheckOk": true,
                      "racyCheckOk": true
                    }
                """.trimIndent()

                Client.WEB -> """
                    {
                      "videoId": "$videoId",
                      "context": {
                        "client": {
                          "clientName": "WEB",
                          "clientVersion": "$WEB_VERSION",
                          "hl": "en",
                          "gl": "US",
                          "userAgent": "$WEB_UA"
                        }
                      },
                      "contentCheckOk": true,
                      "racyCheckOk": true
                    }
                """.trimIndent()
            }

        // ── Headers InnerTube par client ──────────────────────────────────────

        private fun buildHeaders(client: Client, sapisid: String?): Map<String, String> =
            buildMap {
                put("Content-Type",             "application/json; charset=UTF-8")
                put("Accept-Language",          "en-US,en;q=0.9")
                put("X-Goog-Api-Format-Version","1")
                // Ne PAS mettre Origin/Referer pour TV_EMBEDDED (embed context)
                if (client != Client.TV_EMBEDDED) {
                    put("Origin",  ORIGIN)
                    put("Referer", "$ORIGIN/")
                }
                when (client) {
                    Client.TV_EMBEDDED -> {
                        put("User-Agent",               WEB_UA)
                        put("X-YouTube-Client-Name",    "85")
                        put("X-YouTube-Client-Version", TV_EMBED_VERSION)
                    }
                    Client.IOS -> {
                        put("User-Agent",               IOS_UA)
                        put("X-YouTube-Client-Name",    "5")
                        put("X-YouTube-Client-Version", IOS_VERSION)
                    }
                    Client.ANDROID -> {
                        put("User-Agent",               ANDROID_UA)
                        put("X-YouTube-Client-Name",    "3")
                        put("X-YouTube-Client-Version", ANDROID_VERSION)
                    }
                    Client.WEB -> {
                        put("User-Agent",               WEB_UA)
                        put("X-YouTube-Client-Name",    "1")
                        put("X-YouTube-Client-Version", WEB_VERSION)
                        if (sapisid != null) {
                            val auth = computeSapisidhash(sapisid)
                            put("Authorization",        auth)
                            put("X-Goog-AuthUser",      "0")
                        }
                    }
                }
            }

        // ── Utilitaires ───────────────────────────────────────────────────────

        fun computeSapisidhash(sapisid: String, origin: String = ORIGIN): String {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val input = "$ts $sapisid $origin"
            val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
            val hash = digest.joinToString("") { "%02x".format(it) }
            return "SAPISIDHASH ${ts}_${hash}"
        }

        fun extractSapisid(cookies: String?): String? {
            if (cookies.isNullOrEmpty()) return null
            return cookies.split(";").mapNotNull { entry ->
                val parts = entry.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap().let { it["SAPISID"] ?: it["__Secure-3PAPISID"] }
        }

        fun extractVideoId(url: String): String? {
            if (url.isBlank()) return null
            val patterns = listOf(
                Regex("""(?:v=|v/|embed/|shorts/|youtu\.be/)([a-zA-Z0-9_-]{11})"""),
                Regex("""^([a-zA-Z0-9_-]{11})$""")
            )
            for (p in patterns) {
                p.find(url)?.groupValues?.get(1)?.let { return it }
            }
            return null
        }

        fun isYouTubePageUrl(url: String): Boolean =
            url.contains("youtube.com/watch")   ||
            url.contains("youtube.com/shorts/") ||
            url.contains("youtu.be/")           ||
            url.contains("m.youtube.com/watch") ||
            url.contains("music.youtube.com/watch")

        fun isYouTubeUrl(url: String): Boolean =
            isYouTubePageUrl(url)           ||
            url.contains("youtube.com/embed/") ||
            url.contains("googlevideo.com")

        fun isGoogleVideoUrl(url: String): Boolean =
            url.contains("googlevideo.com") || url.contains("/videoplayback")
    }

    // ── Data classes ──────────────────────────────────────────────────────────

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
        val isMuxed: Boolean get() = hasVideo && hasAudio
        val fileExtension: String get() = when {
            mimeType.contains("mp4")  -> "mp4"
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
        fun bestMuxedFormat(): YouTubeStream? =
            muxedFormats.sortedByDescending { it.height }.firstOrNull()

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
        data class Muxed(val stream: YouTubeStream)      : DownloadStrategy()
        data class Adaptive(val video: YouTubeStream, val audio: YouTubeStream) : DownloadStrategy()
        data class Hls(val manifestUrl: String)          : DownloadStrategy()
        data class Error(val message: String)            : DownloadStrategy()
    }

    // ── Extraction principale ─────────────────────────────────────────────────

    /**
     * Extrait les streams pour une URL YouTube.
     * Essaie les clients dans l'ordre : TV_EMBEDDED -> IOS -> ANDROID -> WEB.
     */
    suspend fun extract(
        youtubeUrl: String,
        pageUrl: String? = null,
        cookies: String? = null
    ): ExtractionResult? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: extractVideoId(pageUrl ?: "")
            ?: run {
                Log.e(TAG, "Impossible d'extraire l'ID video: youtubeUrl=$youtubeUrl pageUrl=$pageUrl")
                return@withContext null
            }
        Log.d(TAG, "=== InnerTube extraction pour videoId=$videoId ===")
        Log.d(TAG, "  youtubeUrl=$youtubeUrl")
        Log.d(TAG, "  pageUrl=$pageUrl")
        Log.d(TAG, "  cookies=${cookies?.take(80)}...")

        val sapisid = extractSapisid(cookies)
        Log.d(TAG, "  sapisid=${if (sapisid != null) "present (${sapisid.take(6)}...)" else "absent"}")

        // ── Ordre de priorite des clients ─────────────────────────────────────
        for (client in listOf(Client.TV_EMBEDDED, Client.IOS, Client.ANDROID, Client.WEB)) {
            // WEB necessite sapisidhash
            if (client == Client.WEB && sapisid == null) {
                Log.d(TAG, "[$videoId] WEB saute (pas de SAPISID)")
                continue
            }

            Log.d(TAG, "[$videoId] Essai client $client...")
            val result = callApi(videoId, client, if (client == Client.WEB) sapisid else null)
            if (result != null) {
                Log.d(TAG, "[$videoId] SUCCES avec $client")
                return@withContext result
            }
            Log.d(TAG, "[$videoId] ECHEC avec $client, essai client suivant...")
        }

        Log.e(TAG, "[$videoId] TOUS les clients ont echoue.")
        null
    }

    suspend fun extractByVideoId(videoId: String): ExtractionResult? =
        withContext(Dispatchers.IO) {
            callApi(videoId, Client.TV_EMBEDDED, null)
                ?: callApi(videoId, Client.IOS, null)
                ?: callApi(videoId, Client.ANDROID, null)
        }

    // ── Appel InnerTube API via OkHttp ───────────────────────────────────────

    private fun callApi(
        videoId: String,
        client: Client,
        sapisid: String?
    ): ExtractionResult? {
        val jsonBody = buildBody(videoId, client, sapisid)
        val headers = buildHeaders(client, sapisid)

        Log.d(TAG, "[$client][$videoId] Requete vers InnerTube API...")
        Log.d(TAG, "[$client][$videoId] Body: ${jsonBody.take(300)}...")

        val request = Request.Builder()
            .url(INNERTUBE_URL)
            .post(jsonBody.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string() ?: ""

            Log.d(TAG, "[$client][$videoId] HTTP $code | Body length=${responseBody.length}")

            if (code != 200) {
                Log.e(TAG, "[$client][$videoId] HTTP ERROR $code: ${responseBody.take(500)}")
                return null
            }

            // Log la reponse (premiers 500 chars pour debug)
            Log.d(TAG, "[$client][$videoId] Response: ${responseBody.take(500)}")

            parseResponse(videoId, responseBody, client)

        } catch (e: Exception) {
            Log.e(TAG, "[$client][$videoId] EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    // ── Parsing de la reponse ─────────────────────────────────────────────────

    private fun parseResponse(
        videoId: String,
        json: String,
        client: Client
    ): ExtractionResult? {
        return try {
            val root = JSONObject(json)

            val playability = root.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            val reason = playability?.optString("reason", "")

            Log.d(TAG, "[$client][$videoId] playabilityStatus=$status reason=$reason")

            when (status) {
                "ERROR" -> {
                    Log.e(TAG, "[$client][$videoId] ERROR: $reason")
                    return null
                }
                "LOGIN_REQUIRED" -> {
                    val messages = playability?.optJSONArray("messages")
                    val msg = messages?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it) }.joinToString("; ")
                    } ?: ""
                    Log.w(TAG, "[$client][$videoId] LOGIN_REQUIRED: $msg")
                    return null
                }
                "UNPLAYABLE" -> {
                    val messages = playability?.optJSONArray("messages")
                    val msg = messages?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it) }.joinToString("; ")
                    } ?: reason
                    Log.w(TAG, "[$client][$videoId] UNPLAYABLE: $msg")
                    return null
                }
                "AGE_CHECK_REQUIRED" -> {
                    Log.w(TAG, "[$client][$videoId] AGE_CHECK_REQUIRED")
                    return null
                }
                "CONTENT_CHECK_REQUIRED" -> {
                    Log.w(TAG, "[$client][$videoId] CONTENT_CHECK_REQUIRED")
                    return null
                }
                "LIVE_STREAM_OFFLINE", "LIVE_STREAM_UNAVAILABLE" -> {
                    Log.w(TAG, "[$client][$videoId] $status: $reason")
                    return null
                }
                null, "OK" -> { /* continuer */ }
                else -> {
                    Log.w(TAG, "[$client][$videoId] Status inattendu: $status — on continue quand meme")
                }
            }

            val videoDetails = root.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title") ?: "YouTube_$videoId"
            val duration = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
            val isLive = videoDetails?.optBoolean("isLive", false) == true
            val thumbs = videoDetails?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbnail = if (thumbs != null && thumbs.length() > 0)
                thumbs.getJSONObject(thumbs.length() - 1).optString("url") else ""

            Log.d(TAG, "[$client][$videoId] Title=\"$title\" duration=${duration}s isLive=$isLive")

            val streamingData = root.optJSONObject("streamingData")
            if (streamingData == null) {
                Log.w(TAG, "[$client][$videoId] streamingData=null (status=$status)")
                return null
            }

            val hlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.isNotEmpty() }
            val muxed     = mutableListOf<YouTubeStream>()
            val videoOnly = mutableListOf<YouTubeStream>()
            val audioOnly = mutableListOf<YouTubeStream>()

            streamingData.optJSONArray("formats")?.let { arr ->
                for (i in 0 until arr.length())
                    parseFormat(arr.getJSONObject(i), true, true)?.let { muxed.add(it) }
            }
            streamingData.optJSONArray("adaptiveFormats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj  = arr.getJSONObject(i)
                    val mime = obj.optString("mimeType")
                    when {
                        mime.startsWith("video/") ->
                            parseFormat(obj, true, false)?.let { videoOnly.add(it) }
                        mime.startsWith("audio/") ->
                            parseFormat(obj, false, true)?.let { audioOnly.add(it) }
                    }
                }
            }

            Log.d(TAG, "[$client][$videoId] Formats trouves: muxed=${muxed.size} videoOnly=${videoOnly.size} audioOnly=${audioOnly.size} hls=${hlsUrl != null}")

            // Si aucun format muxe ET pas de video ET pas de HLS -> echec
            if (muxed.isEmpty() && videoOnly.isEmpty() && hlsUrl == null) {
                // Pour les lives, HLS peut etre le seul format
                if (isLive && hlsUrl != null) {
                    // OK pour un live
                } else {
                    Log.w(TAG, "[$client][$videoId] Aucun format disponible dans streamingData")
                    // Log le streamingData pour debug
                    Log.d(TAG, "[$client][$videoId] streamingData brut: ${streamingData.toString().take(800)}")
                    return null
                }
            }

            // Si on a de la video adaptive mais pas d'audio -> essayer de trouver du muxed
            if (muxed.isEmpty() && videoOnly.isNotEmpty() && audioOnly.isEmpty()) {
                Log.w(TAG, "[$client][$videoId] Video adaptive sans audio — le muxed est absent, on continue avec ce qu'on a")
            }

            Log.d(TAG, "[$client][$videoId] EXTRACTION OK: \"$title\" | " +
                "muxed=${muxed.size} video=${videoOnly.size} audio=${audioOnly.size}")

            ExtractionResult(
                videoId = videoId, title = title, duration = duration, thumbnail = thumbnail,
                muxedFormats     = muxed.sortedByDescending { it.height },
                videoOnlyFormats = videoOnly.sortedByDescending { it.height },
                audioOnlyFormats = audioOnly.sortedByDescending { it.contentLength },
                hlsManifestUrl   = hlsUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "[$client][$videoId] Parsing erreur: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    // ── Parsing d'un format ───────────────────────────────────────────────────

    private fun parseFormat(obj: JSONObject, hasVideo: Boolean, hasAudio: Boolean): YouTubeStream? {
        return try {
            val url = obj.optString("url").takeIf { it.isNotEmpty() }
                ?: decodeCipher(
                    obj.optString("signatureCipher").takeIf { it.isNotEmpty() }
                        ?: obj.optString("cipher").takeIf { it.isNotEmpty() }
                        ?: return null
                ) ?: return null

            val mimeType = obj.optString("mimeType").substringBefore(";").trim()
            val height   = obj.optInt("height", 0)

            YouTubeStream(
                url          = url,
                mimeType     = mimeType,
                quality      = obj.optString("quality"),
                qualityLabel = obj.optString("qualityLabel").takeIf { it.isNotEmpty() }
                               ?: if (height > 0) "${height}p" else "Audio",
                width        = obj.optInt("width", 0),
                height       = height,
                contentLength= obj.optString("contentLength").toLongOrNull()
                               ?: obj.optLong("contentLength", 0L),
                hasVideo     = hasVideo,
                hasAudio     = hasAudio,
                itag         = obj.optInt("itag", 0),
                fps          = obj.optInt("fps", 0),
                audioQuality = obj.optString("audioQuality").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Format parse erreur: ${e.message}")
            null
        }
    }

    private fun decodeCipher(cipher: String?): String? {
        cipher ?: return null
        return try {
            val params = cipher.split("&").associate { p ->
                val i = p.indexOf('=')
                if (i < 0) p to "" else p.substring(0, i) to URLDecoder.decode(p.substring(i + 1), "UTF-8")
            }
            val base = params["url"] ?: return null
            buildString {
                append(base)
                // sig = signature deja decodee
                params["sig"]?.let { append("&").append(params["sp"] ?: "signature").append("=").append(it) }
                // s = signature obfusquee (necessite decode JS — on ne peut pas la decoder ici)
                // On l'ajoute quand meme au cas ou elle passe directement
                params["s"]?.let   { append("&").append(params["sp"] ?: "signature").append("=").append(it) }
                params["n"]?.let   { append("&n=").append(it) }
                params["dn"]?.let  { append("&dn=").append(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cipher decode erreur: ${e.message}")
            null
        }
    }
}
