package com.hitif.videodownloader.network

import android.webkit.WebResourceRequest
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType
import com.hitif.videodownloader.model.MediaQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Analyses intercepted WebView requests and sniffs media URLs.
 * Mirrors the logic of the HITIF Chrome extension's content script.
 */
class MediaDetector(
    private val onMediaFound: (MediaItem) -> Unit
) {
    // Deduplicate by normalised URL
    private val seen = ConcurrentHashMap<String, Boolean>()

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)

    // ── URL pattern lists (mirrors extension's heuristics) ──────────────────

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "webm", "mkv", "avi", "mov", "flv", "m4v",
        "3gp", "mts", "m2ts", "vob", "ogv"
    )
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav"
    )
    private val STREAM_EXTENSIONS = mapOf(
        "m3u8" to MediaType.HLS,
        "m3u"  to MediaType.HLS,
        "mpd"  to MediaType.DASH
    )

    private val VIDEO_MIME_PREFIXES = listOf("video/", "application/x-mpegurl",
        "application/vnd.apple.mpegurl", "application/dash+xml")
    private val AUDIO_MIME_PREFIXES = listOf("audio/")

    private val BLOCKED_HOSTS = setOf(
        "googlevideo.com", "googleads.g.doubleclick.net", "doubleclick.net",
        "ads.youtube.com", "static.ads-twitter.com"
    )
    private val SKIP_PATTERNS = listOf(
        Regex("manifest\\.json$"), Regex("thumbnail"), Regex("poster"),
        Regex("preview"), Regex("storyboard"), Regex("/ad/"), Regex("/ads/"),
        Regex("beacon"), Regex("analytics"), Regex("tracking"),
        // HLS / DASH segment files — only the playlist is useful
        Regex("/seg-?\\d"), Regex("/segment-?\\d"), Regex("/chunk-?\\d"),
        Regex("/part-?\\d"), Regex("-v\\d+-a\\d+\\.ts"),
        Regex("\\.ts\\?"), Regex("init_"), Regex("/frag-?\\d"),
        // Key files
        Regex("\\.key$"), Regex("/key/"),
        // Subtitle segments
        Regex("/subtitles/"), Regex("\\.vtt$"), Regex("\\.srt$"),
        // HLS variant playlists — master.m3u8 is enough, skip index-v*-a*.m3u8
        Regex("/index-v\\d+-a\\d+\\.m3u8$"),
        Regex("/index_v\\d+_a\\d+\\.m3u8$"),
        Regex("playlist-?\\d+\\.m3u8$", RegexOption.IGNORE_CASE)
    )

    // Track which HLS/DASH base URLs we've already emitted (deduplicate per stream)
    private val emittedStreamBases = ConcurrentHashMap<String, Boolean>()

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Called from WebViewClient.shouldInterceptRequest — runs on a background thread.
     */
    fun analyse(request: WebResourceRequest, pageUrl: String, pageTitle: String) {
        val url = request.url.toString()
        analyseUrl(url, request.requestHeaders ?: emptyMap(), pageUrl, pageTitle)
    }

    /**
     * Analyse a raw URL string (e.g. from JS bridge).
     */
    fun analyseUrl(url: String, headers: Map<String, String> = emptyMap(),
                   pageUrl: String = "", pageTitle: String = "") {
        val clean = url.substringBefore('?').substringBefore('#').lowercase().trim()
        val key   = url.substringBefore('?').substringBefore('#')
        if (seen.putIfAbsent(key, true) != null) return   // already processed

        // Quick path: skip obviously irrelevant
        if (shouldSkip(clean)) return

        val ext = clean.substringAfterLast('.', "")

        when {
            ext in STREAM_EXTENSIONS -> {
                // Deduplicate: only emit one m3u8/mpd per base CDN path
                val baseUrl = url.substringBefore('?').substringBefore('#')
                    .replace(Regex("master|index-v[0-9]+-a[0-9]+|index_v[0-9]+_a[0-9]+|playlist-?[0-9]+|index"), "*")
                if (emittedStreamBases.putIfAbsent(baseUrl, true) != null) return
                emit(buildItem(url, ext, STREAM_EXTENSIONS[ext]!!, headers, -1L, pageUrl, pageTitle))
            }
            ext in VIDEO_EXTENSIONS -> {
                scope.launch { sniffAndEmit(url, headers, MediaType.VIDEO, pageUrl, pageTitle) }
            }
            ext in AUDIO_EXTENSIONS -> {
                scope.launch { sniffAndEmit(url, headers, MediaType.AUDIO, pageUrl, pageTitle) }
            }
            else -> {
                // HEAD-sniff only if URL looks interesting (no extension or cdn path)
                if (looksLikeMedia(url)) {
                    scope.launch { sniffAndEmit(url, headers, MediaType.UNKNOWN, pageUrl, pageTitle) }
                }
            }
        }
    }

    fun reset() {
        seen.clear()
        emittedStreamBases.clear()
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun shouldSkip(cleanUrl: String): Boolean {
        BLOCKED_HOSTS.forEach { host -> if (cleanUrl.contains(host)) return true }
        SKIP_PATTERNS.forEach { re -> if (re.containsMatchIn(cleanUrl)) return true }
        return false
    }

    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/video/") || lower.contains("/audio/") ||
               lower.contains("/media/") || lower.contains("/stream/") ||
               lower.contains("/hls/")  || lower.contains("/dash/") ||
               lower.contains("blob:")  || lower.contains("cdn") ||
               lower.contains("chunked") || lower.contains("segment")
    }

    private suspend fun sniffAndEmit(
        url: String, headers: Map<String, String>,
        hintType: MediaType, pageUrl: String, pageTitle: String
    ) {
        try {
            val req = Request.Builder().url(url).method("HEAD", null).apply {
                headers.forEach { (k, v) ->
                    if (k.lowercase() !in listOf("host", "content-length")) addHeader(k, v)
                }
                header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            }.build()

            val resp = http.newCall(req).execute()
            val mime = resp.header("Content-Type", "") ?: ""
            val size = resp.header("Content-Length", "-1")?.toLongOrNull() ?: -1L
            resp.close()

            val resolvedType = when {
                VIDEO_MIME_PREFIXES.any { mime.startsWith(it) } -> {
                    if (mime.contains("mpegurl") || mime.contains("dash")) MediaType.HLS
                    else MediaType.VIDEO
                }
                AUDIO_MIME_PREFIXES.any { mime.startsWith(it) } -> MediaType.AUDIO
                hintType != MediaType.UNKNOWN -> hintType
                else -> return   // not media
            }

            emit(buildItem(url, mime.substringAfter('/').substringBefore(';'),
                resolvedType, headers, size, pageUrl, pageTitle))
        } catch (_: Exception) {
            // Network error — if hint says VIDEO/AUDIO, still emit
            if (hintType == MediaType.VIDEO || hintType == MediaType.AUDIO) {
                emit(buildItem(url, "", hintType, headers, -1L, pageUrl, pageTitle))
            }
        }
    }

    private fun buildItem(
        url: String, extOrMime: String, type: MediaType,
        headers: Map<String, String>, size: Long,
        pageUrl: String, pageTitle: String
    ): MediaItem {
        val quality = guessQuality(url, headers)

        // For HLS/DASH streams: use page title as filename instead of raw m3u8/mpd name
        val safeName = if (type == MediaType.HLS || type == MediaType.DASH) {
            val cleanTitle = pageTitle
                .replace(Regex("[\\/:*?\"<>|]+"), " ")
                .replace(Regex("\\s+"), "_")
                .replace(Regex("-{2,}"), "-")
                .trim('_', '-', ' ')
            if (cleanTitle.length >= 3) {
                val qualitySuffix = when (quality) {
                    com.hitif.videodownloader.model.MediaQuality.ULTRA -> "_4K"
                    com.hitif.videodownloader.model.MediaQuality.HIGH -> "_1080p"
                    com.hitif.videodownloader.model.MediaQuality.MEDIUM -> "_720p"
                    else -> ""
                }
                "${cleanTitle.take(80)}${qualitySuffix}.mp4"
            } else {
                "hitif_${System.currentTimeMillis() / 1000}.mp4"
            }
        } else {
            val rawName = url.substringBefore('?').substringAfterLast('/')
            rawName.ifBlank { "media_${System.currentTimeMillis()}" }
                .let { if (!it.contains('.')) "$it.${extOrMime.take(4)}" else it }
        }

        return MediaItem(
            url        = url,
            filename   = safeName,
            mimeType   = extOrMime,
            mediaType  = type,
            quality    = quality,
            sizeBytes  = size,
            pageUrl    = pageUrl,
            pageTitle  = pageTitle
        )
    }

    private fun guessQuality(url: String, headers: Map<String, String>): MediaQuality {
        val lower = url.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> MediaQuality.ULTRA
            lower.contains("1080") || lower.contains("fhd") -> MediaQuality.HIGH
            lower.contains("720")  || lower.contains("hd")  -> MediaQuality.MEDIUM
            lower.contains("480")  || lower.contains("360") -> MediaQuality.LOW
            else -> MediaQuality.UNKNOWN
        }
    }

    private fun emit(item: MediaItem) {
        onMediaFound(item)
    }
}
