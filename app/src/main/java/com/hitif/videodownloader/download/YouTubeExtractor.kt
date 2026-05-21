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
 * STRATEGIE MULTI-CLIENT (ordre de priorité) :
 * ─────────────────────────────────────────────────────────────────────────
 * 1. TV_EMBEDDED  (clientId=85) — Pas de PO token requis. Le plus fiable.
 * 2. IOS          (clientId=5)  — Fallback. Pas de PO token. URLs directes.
 * 3. ANDROID      (clientId=3)  — Fallback.
 * 4. WEB + sapisidhash — Si cookies SAPISID disponibles (utilisateur connecté).
 *
 * CAUSE DU BUG PRÉCÉDENT :
 * ─────────────────────────────────────────────────────────────────────────
 * L'unique httpClient utilisait WebViewCookieJar pour TOUS les clients.
 * Envoyer les cookies de session YouTube aux clients TV_EMBEDDED et IOS
 * signalait à YouTube une session navigateur → vérification PO token
 * → rejet de TOUS les clients avec streamingData=null.
 *
 * CORRECTION :
 * - cleanClient  : sans cookies → TV_EMBEDDED, IOS, ANDROID
 * - sessionClient: avec WebViewCookieJar → WEB seulement (bénéficie des cookies)
 */
class YouTubeExtractor {

    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val INNERTUBE_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private const val ORIGIN = "https://www.youtube.com"

        // ── Versions clients ──────────────────────────────────────────────────
        private const val TV_EMBED_VERSION  = "7.20250409"
        private const val IOS_VERSION       = "20.16.7"
        private const val IOS_DEVICE        = "iPhone16,2"
        private const val IOS_UA =
            "com.google.ios.youtube/$IOS_VERSION (iPhone16,2; U; CPU iOS 18_5_0 like Mac OS X)"
        private const val ANDROID_VERSION   = "20.16.7"
        private const val ANDROID_UA =
            "com.google.android.youtube/$ANDROID_VERSION " +
            "(Linux; U; Android 15; en_US) gzip"
        private const val WEB_VERSION       = "2.20250513.00.00"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        enum class Client { TV_EMBEDDED, IOS, ANDROID, WEB }

        // ── CLIENT PROPRE (sans cookies) — TV_EMBEDDED, IOS, ANDROID ─────────
        // CRITIQUE : ne pas utiliser WebViewCookieJar ici.
        // Les cookies YouTube envoyés avec ces clients déclenchent la vérification
        // PO token de YouTube, ce qui fait échouer l'extraction.
        private val cleanClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        // ── CLIENT SESSION (avec cookies WebView) — WEB uniquement ────────────
        // Utilisé seulement pour le client WEB qui bénéficie des cookies de session
        // YouTube (SAPISID → sapisidhash) pour accéder aux vidéos age-restreintes.
        private val sessionClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .cookieJar(WebViewCookieJar())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        // ── Corps de requête InnerTube ─────────────────────────────────────────

        private fun buildBody(videoId: String, client: Client): String = when (client) {
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

        // ── Headers InnerTube ─────────────────────────────────────────────────

        private fun buildHeaders(client: Client, sapisid: String?): Map<String, String> =
            buildMap {
                put("Content-Type",              "application/json; charset=UTF-8")
                put("Accept-Language",           "en-US,en;q=0.9")
                put("X-Goog-Api-Format-Version", "1")
                put("Origin",  ORIGIN)
                put("Referer", "$ORIGIN/")
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
                            put("Authorization",  computeSapisidhash(sapisid))
                            put("X-Goog-AuthUser","0")
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
            isYouTubePageUrl(url)              ||
            url.contains("youtube.com/embed/") ||
            url.contains("googlevideo.com")

        fun isGoogleVideoUrl(url: String): Boolean =
            url.contains("googlevideo.com") || url.contains("/videoplayback")

        /** UA a utiliser pour le telechargement des streams extraits par ce client */
        fun downloadUserAgent(client: Client): String = when (client) {
            Client.TV_EMBEDDED -> WEB_UA
            Client.IOS          -> IOS_UA
            Client.ANDROID      -> ANDROID_UA
            Client.WEB          -> WEB_UA
        }
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
        val audioQuality: String? = null,
        val userAgent: String? = null
    ) {
        val isMuxed: Boolean get() = hasVideo && hasAudio
        val fileExtension: String get() = when {
            mimeType.contains("mp4")       -> "mp4"
            mimeType.contains("webm")      -> "webm"
            mimeType.contains("audio/mp4") -> "m4a"
            else                           -> "mp4"
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
        val hlsManifestUrl: String?,
        /** Client qui a reussi l'extraction — utilise pour prendre les bonnes decisions
         *  au telechargement (envoyer ou non les cookies, quels headers envoyer). */
        val extractedWithClient: Client = Client.TV_EMBEDDED,
        /** true si l'extraction a utilise sessionClient (avec cookies WebView).
         *  false si cleanClient (sans cookies) — TV_EMBEDDED, IOS, ANDROID.
         *  CRITIQUE : ne JAMAIS envoyer de cookies au telechargement si false. */
        val usedSessionClient: Boolean = false
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
        data class Muxed(val stream: YouTubeStream)                              : DownloadStrategy()
        data class Adaptive(val video: YouTubeStream, val audio: YouTubeStream) : DownloadStrategy()
        data class Hls(val manifestUrl: String)                                  : DownloadStrategy()
        data class Error(val message: String)                                    : DownloadStrategy()
    }

    // ── Extraction principale ─────────────────────────────────────────────────

    suspend fun extract(
        youtubeUrl: String,
        pageUrl: String? = null,
        cookies: String? = null
    ): ExtractionResult? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: extractVideoId(pageUrl ?: "")
            ?: run {
                Log.e(TAG, "Impossible d'extraire l'ID vidéo: url=$youtubeUrl page=$pageUrl")
                return@withContext null
            }

        Log.d(TAG, "=== InnerTube extraction videoId=$videoId ===")

        val sapisid = extractSapisid(cookies)
        Log.d(TAG, "  sapisid=${if (sapisid != null) "présent" else "absent"}")

        // 1. TV_EMBEDDED — cleanClient, pas de cookies
        Log.d(TAG, "[$videoId] Essai TV_EMBEDDED (clean, pas de cookies)...")
        callApi(videoId, Client.TV_EMBEDDED, null, useSession = false)
            ?.let { return@withContext it }

        // 2. IOS — cleanClient, pas de cookies
        Log.d(TAG, "[$videoId] TV_EMBEDDED échoué → essai IOS...")
        callApi(videoId, Client.IOS, null, useSession = false)
            ?.let { return@withContext it }

        // 3. ANDROID — cleanClient, pas de cookies
        Log.d(TAG, "[$videoId] IOS échoué → essai ANDROID...")
        callApi(videoId, Client.ANDROID, null, useSession = false)
            ?.let { return@withContext it }

        // 4. WEB — sessionClient avec cookies (dernier recours)
        Log.d(TAG, "[$videoId] ANDROID échoué → essai WEB (avec cookies)...")
        callApi(videoId, Client.WEB, sapisid, useSession = true)
            ?.let { return@withContext it }

        Log.e(TAG, "[$videoId] TOUS les clients ont échoué.")
        null
    }

    suspend fun extractByVideoId(videoId: String): ExtractionResult? =
        withContext(Dispatchers.IO) {
            callApi(videoId, Client.TV_EMBEDDED, null, useSession = false)
                ?: callApi(videoId, Client.IOS,        null, useSession = false)
                ?: callApi(videoId, Client.ANDROID,    null, useSession = false)
        }

    // ── Appel InnerTube API ───────────────────────────────────────────────────

    private fun callApi(
        videoId: String,
        client: Client,
        sapisid: String?,
        useSession: Boolean      // true = sessionClient (cookies WebView), false = cleanClient
    ): ExtractionResult? {
        val body    = buildBody(videoId, client)
        val headers = buildHeaders(client, sapisid)
        val http    = if (useSession) sessionClient else cleanClient

        val request = Request.Builder()
            .url(INNERTUBE_URL)
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        return try {
            val response     = http.newCall(request).execute()
            val code         = response.code
            val responseBody = response.body?.string() ?: ""

            Log.d(TAG, "[$client][$videoId] HTTP $code | body=${responseBody.length}B")

            if (code != 200) {
                Log.e(TAG, "[$client][$videoId] HTTP $code: ${responseBody.take(300)}")
                return null
            }
            if (responseBody.isEmpty()) {
                Log.e(TAG, "[$client][$videoId] Réponse vide")
                return null
            }

            parseResponse(videoId, responseBody, client, useSession)
        } catch (e: Exception) {
            Log.e(TAG, "[$client][$videoId] Exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parseResponse(videoId: String, json: String, client: Client, usedSession: Boolean): ExtractionResult? {
        return try {
            val root        = JSONObject(json)
            val playability = root.optJSONObject("playabilityStatus")
            val status      = playability?.optString("status")
            val reason      = playability?.optString("reason", "") ?: ""

            Log.d(TAG, "[$client][$videoId] status=$status reason=$reason")

            when (status) {
                "ERROR"                              -> { Log.e(TAG, "[$client][$videoId] ERROR: $reason"); return null }
                "LOGIN_REQUIRED"                     -> { Log.w(TAG, "[$client][$videoId] LOGIN_REQUIRED"); return null }
                "UNPLAYABLE"                         -> { Log.w(TAG, "[$client][$videoId] UNPLAYABLE: $reason"); return null }
                "AGE_CHECK_REQUIRED"                 -> { Log.w(TAG, "[$client][$videoId] AGE_CHECK_REQUIRED"); return null }
                "CONTENT_CHECK_REQUIRED"             -> { Log.w(TAG, "[$client][$videoId] CONTENT_CHECK_REQUIRED"); return null }
                "LIVE_STREAM_OFFLINE",
                "LIVE_STREAM_UNAVAILABLE"            -> { Log.w(TAG, "[$client][$videoId] $status"); return null }
                null, "OK"                           -> { /* continuer */ }
                else -> Log.w(TAG, "[$client][$videoId] Status inattendu: $status")
            }

            val videoDetails = root.optJSONObject("videoDetails")
            val title    = videoDetails?.optString("title")    ?: "YouTube_$videoId"
            val duration = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
            val isLive   = videoDetails?.optBoolean("isLive", false) == true
            val thumbs   = videoDetails?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbnail= if (thumbs != null && thumbs.length() > 0)
                thumbs.getJSONObject(thumbs.length() - 1).optString("url") else ""

            val streamingData = root.optJSONObject("streamingData")
            if (streamingData == null) {
                Log.w(TAG, "[$client][$videoId] streamingData=null (status=$status isLive=$isLive)")
                return null
            }

            val hlsUrl    = streamingData.optString("hlsManifestUrl").takeIf { it.isNotEmpty() }
            val muxed     = mutableListOf<YouTubeStream>()
            val videoOnly = mutableListOf<YouTubeStream>()
            val audioOnly = mutableListOf<YouTubeStream>()

            val dlUA = downloadUserAgent(client)
            streamingData.optJSONArray("formats")?.let { arr ->
                for (i in 0 until arr.length())
                    parseFormat(arr.getJSONObject(i), true, true, dlUA)?.let { muxed.add(it) }
            }
            streamingData.optJSONArray("adaptiveFormats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj  = arr.getJSONObject(i)
                    val mime = obj.optString("mimeType")
                    when {
                        mime.startsWith("video/") ->
                            parseFormat(obj, true, false, dlUA)?.let { videoOnly.add(it) }
                        mime.startsWith("audio/") ->
                            parseFormat(obj, false, true, dlUA)?.let { audioOnly.add(it) }
                    }
                }
            }

            Log.d(TAG, "[$client][$videoId] muxed=${muxed.size} video=${videoOnly.size} audio=${audioOnly.size} hls=${hlsUrl != null}")

            if (muxed.isEmpty() && videoOnly.isEmpty() && hlsUrl == null) {
                Log.w(TAG, "[$client][$videoId] Aucun format dans streamingData")
                return null
            }

            Log.d(TAG, "[$client][$videoId] SUCCÈS: \"$title\"")

            ExtractionResult(
                videoId            = videoId,
                title              = title,
                duration           = duration,
                thumbnail          = thumbnail,
                muxedFormats       = muxed.sortedByDescending { it.height },
                videoOnlyFormats   = videoOnly.sortedByDescending { it.height },
                audioOnlyFormats   = audioOnly.sortedByDescending { it.contentLength },
                hlsManifestUrl     = hlsUrl,
                extractedWithClient = client,
                usedSessionClient  = usedSession
            )
        } catch (e: Exception) {
            Log.e(TAG, "[$client][$videoId] Parsing erreur: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ── Format parsing ────────────────────────────────────────────────────────

    private fun parseFormat(obj: JSONObject, hasVideo: Boolean, hasAudio: Boolean, downloadUA: String): YouTubeStream? {
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
                audioQuality = obj.optString("audioQuality").takeIf { it.isNotEmpty() },
                userAgent    = downloadUA
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
                params["sig"]?.let { append("&").append(params["sp"] ?: "signature").append("=").append(it) }
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
