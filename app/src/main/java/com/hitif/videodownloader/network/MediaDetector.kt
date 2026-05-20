package com.hitif.videodownloader.network

import android.util.Log
import android.webkit.WebResourceRequest
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType
import com.hitif.videodownloader.model.MediaQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Analyses intercepted WebView requests and response bodies, then sniffs
 * media URLs. Mirrors the logic of the HITIF Chrome extension's content script.
 *
 * Detection sources:
 *   1. shouldInterceptRequest — URL pattern matching + HEAD sniff
 *   2. JS bridge onMedia() — URLs found by injected JavaScript
 *   3. JS bridge onXhrResponse() — response bodies containing M3U8, video URLs
 */
class MediaDetector(
    private val onMediaFound: (MediaItem) -> Unit
) {
    companion object {
        private const val TAG = "MediaDetector"

        /** Regex patterns to extract video URLs from JSON/HTML response bodies */
        private val VIDEO_URL_PATTERNS = listOf(
            // JSON: common video URL keys
            Regex(""""(?:url|src|file|playback_url|stream_url|video_url|download_url|source)\s*:\s*"(https?://[^"]+\.(?:m3u8|mpd|mp4|webm|mkv|ts)[^"]*)""""),
            Regex(""""(?:url|src|file|playback_url|stream_url|video_url|download_url|source)\s*:\s*"(https?://[^"]*(?:/video/|/stream/|/media/|/hls/|/dash/)[^"]*)""""),
            // Generic: quoted URLs with video extensions
            Regex(""""(https?://[^"'\\s]+\.(?:m3u8|mpd)[^"'\\s]*)""""),
            Regex(""""(https?://[^"'\\s]+\.(?:mp4|webm|mkv)(?:\?[^"'\\s]*)?)""""),
            // Protocol-relative or bare URLs in source maps / configs
            Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mpd)(?:\?[^\s"'<>]*)?)"""),
        )
    }

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

    /** Extra MIME types that indicate media when URL also looks like media.
     *  NOTE: "application/octet-stream" is removed here because many non-media
     *  resources (CSS, JS bundles, JSON configs) use this MIME type, causing
     *  false positives on YouTube and other sites.
     */
    private val EXTRA_MEDIA_MIME = setOf(
        "application/mp4", "video/mp2t", "video/MP2T"
    )

    private val BLOCKED_HOSTS = setOf(
        // googlevideo.com is now ALLOWED — YouTube signed URLs come from this host
        "googleads.g.doubleclick.net", "doubleclick.net",
        "ads.youtube.com", "static.ads-twitter.com"
    )
    /** Filenames that are known internal app assets and must NEVER be detected as media.
     *  These are bundled inside the APK (assets/raw folder) and loaded by the WebView.
     *  If they appear in the media panel, it means shouldInterceptRequest is picking them up.
     */
    private val BLOCKED_FILENAMES = setOf(
        "success.mp3", "open.mp3", "no_input.mp3", "failure.mp3",
        "notification.mp3", "error.mp3", "click.mp3",
        "download_complete.mp3", "download_start.mp3",
        "download_fail.mp3", "button_click.mp3",
        "end.mp3", "start.mp3"
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
        Regex("playlist-?\\d+\\.m3u8$", RegexOption.IGNORE_CASE),
        // ── YouTube non-media resources ─────────────────────────────────
        Regex("youtube\\.com/(favicon|s/generate_)"),
        Regex("youtube\\.com/(yts/img|logo)"),
        Regex("youtube\\.com/embed/"),
        Regex("\\.ggpht\\.com/"),
        Regex("yt3\\.ggpht\\.com/"),
        Regex("ytimg\\.com/"),
        Regex("i\\.ytimg\\.com/"),
        Regex("yt\\.be/"),
        // ── Internal app / local asset URLs ─────────────────────────────
        Regex("^file:///"),
        Regex("^content://"),
        Regex("^android_asset"),
        Regex("^data:"),
        Regex("^blob:"),
        Regex("/android_asset/"),
        Regex("^javascript:"),
        Regex("about:blank")
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

    /**
     * Analyse an XHR/fetch response body for media content.
     * Called from JsInterface.onXhrResponse().
     */
    fun analyseResponse(url: String, content: String, pageUrl: String, pageTitle: String) {
        if (content.length < 15) return
        val lower = content.lowercase()

        when {
            // M3U8 / HLS content — emit the URL as a stream
            lower.contains("#extinf") || lower.contains("#ext-x-stream-inf") -> {
                // Extract direct segment/video URLs from M3U8 if it contains full URLs
                extractVideoUrlsFromContent(url, content, pageUrl, pageTitle)
                // Also emit the M3U8 URL itself as a downloadable stream
                val key = url.substringBefore('?').substringBefore('#')
                if (seen.putIfAbsent(key, true) == null) {
                    emit(buildItem(url, "m3u8", MediaType.HLS, emptyMap(), -1L, pageUrl, pageTitle))
                }
            }
            // DASH manifest
            lower.contains("<mpd") || lower.contains("dash+xml") -> {
                extractVideoUrlsFromContent(url, content, pageUrl, pageTitle)
                val key = url.substringBefore('?').substringBefore('#')
                if (seen.putIfAbsent(key, true) == null) {
                    emit(buildItem(url, "mpd", MediaType.DASH, emptyMap(), -1L, pageUrl, pageTitle))
                }
            }
            // JSON / HTML containing video URLs
            else -> {
                extractVideoUrlsFromContent(url, content, pageUrl, pageTitle)
            }
        }
    }

    /**
     * Emit a pre-built MediaItem directly (bypasses URL analysis).
     * Used by JsInterface for YouTube format extraction where the item
     * is already fully constructed with filename, quality, type, etc.
     *
     * Validates that YouTube items have a googlevideo.com URL to prevent
     * internal app files from being emitted as YouTube media.
     */
    fun emitDirect(item: MediaItem) {
        // YouTube-related URLs: allow YouTube PAGE URLs (youtube.com/watch, youtu.be)
        // and googlevideo.com stream URLs. Block anything else with "youtube" in it
        // (e.g. internal app resources that happen to match).
        if (item.url.contains("youtube") || item.mimeType.contains("youtube") ||
            item.filename.contains("youtube")) {
            val isYoutubePage = item.url.contains("youtube.com/watch") ||
                                item.url.contains("youtube.com/shorts/") ||
                                item.url.contains("youtu.be/")
            if (!isYoutubePage && !item.url.contains("googlevideo.com")) {
                Log.d(TAG, "emitDirect: skipping non-page non-googlevideo YouTube URL: ${item.url.take(80)}")
                return
            }
        }

        // Block known internal asset filenames regardless of source
        val filename = item.url.substringAfterLast('/').substringBefore('?').lowercase()
        if (filename in BLOCKED_FILENAMES) {
            Log.d(TAG, "emitDirect: skipping blocked internal asset: $filename")
            return
        }

        // Block non-http URLs (file://, content://, data:, blob:)
        val cleanUrl = item.url.lowercase().trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            Log.d(TAG, "emitDirect: skipping non-http URL: ${item.url.take(80)}")
            return
        }

        // Dedup key: for YouTube page URLs, include the video ID (query param)
        // because "/watch?v=abc" and "/watch?v=xyz" must be treated as different items.
        // Without this, all YouTube watch URLs share key "https://www.youtube.com/watch"
        // and only the first video is ever detected.
        val key = buildDedupKey(item.url)
        if (seen.putIfAbsent(key, true) != null) return
        emit(item)
    }

    /**
     * Build a deduplication key that is unique per video for YouTube page URLs.
     * For YouTube: "https://www.youtube.com/watch?v=abc123" → includes "v=abc123"
     * For other URLs: standard path-only key (before '?' and '#')
     */
    private fun buildDedupKey(url: String): String {
        if (url.contains("youtube.com/watch") || url.contains("youtube.com/shorts/") ||
            url.contains("youtu.be/")) {
            // For YouTube, the video ID is what makes each URL unique.
            // Include up to the video ID parameter but strip other tracking params.
            val videoIdMatch = Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(url)
            if (videoIdMatch != null) {
                return "yt:${videoIdMatch.groupValues[1]}"
            }
            // Shorts or other YouTube URLs: use the full path up to '?'
            return url.substringBefore('?').substringBefore('#')
        }
        return url.substringBefore('?').substringBefore('#')
    }

    /**
     * Remove all googlevideo.com URLs from the seen set.
     * Called by the 60s YouTube refresh timer to clear stale signed URLs
     * before re-parsing formats.
     */
    fun removeStaleYoutubeUrls() {
        val keysToRemove = seen.keys.filter { it.contains("googlevideo.com") }
        keysToRemove.forEach { seen.remove(it) }
        Log.d(TAG, "Removed ${keysToRemove.size} stale YouTube URLs from dedup")
    }

    fun reset() {
        seen.clear()
        emittedStreamBases.clear()
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun shouldSkip(cleanUrl: String): Boolean {
        // Block known internal app asset filenames
        val filename = cleanUrl.substringAfterLast('/').substringBefore('?').lowercase()
        if (filename in BLOCKED_FILENAMES) {
            Log.d(TAG, "Skipping blocked internal asset: $filename")
            return true
        }

        // Block any URL that is a local file, asset, data URI, or blob
        if (cleanUrl.startsWith("file://") || cleanUrl.startsWith("content://") ||
            cleanUrl.startsWith("android_asset") || cleanUrl.startsWith("data:") ||
            cleanUrl.startsWith("blob:") || cleanUrl.startsWith("javascript:") ||
            cleanUrl.contains("/android_asset/")) {
            return true
        }

        // ── Block GoogleVideo segment URLs ─────────────────────────────
        // These are session-bound URLs (ip=, sig=, expire= parameters) that
        // cannot be downloaded directly (403 Forbidden). YouTubeExtractor
        // handles YouTube downloads via InnerTube API instead.
        if (isGoogleVideoSegmentUrl(cleanUrl)) {
            return true
        }

        BLOCKED_HOSTS.forEach { host -> if (cleanUrl.contains(host)) return true }
        SKIP_PATTERNS.forEach { re -> if (re.containsMatchIn(cleanUrl)) return true }
        return false
    }

    /**
     * Detects GoogleVideo CDN segment URLs that are session-bound.
     * These URLs contain parameters like expire=, ip=, sig= that tie them
     * to the WebView session. Downloading them directly causes 403 Forbidden.
     * YouTubeExtractor handles YouTube via InnerTube API instead.
     */
    private fun isGoogleVideoSegmentUrl(url: String): Boolean {
        if (!url.contains("googlevideo.com")) return false
        return url.contains("expire=") ||
               url.contains("/videoplayback")
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
            // Build headers: forward all except Host/Content-Length/Connection
            val req = Request.Builder().url(url).method("HEAD", null).apply {
                headers.forEach { (k, v) ->
                    val kl = k.lowercase()
                    if (kl !in listOf("host", "content-length", "connection")) {
                        addHeader(k, v)
                    }
                }
                // Forward cookies from the request context
                try {
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    val urlHost = try { URL(url).host ?: "" } catch (_: Exception) { "" }
                    val pageHost = if (pageUrl.isNotBlank()) try { URL(pageUrl).host ?: "" } catch (_: Exception) { "" } else ""
                    for (cookieUrl in listOfNotNull(
                        pageUrl.takeIf { it.isNotBlank() },
                        "https://$urlHost/".takeIf { urlHost.isNotBlank() && urlHost != pageHost }
                    )) {
                        val cookies = cookieManager.getCookie(cookieUrl)
                        if (!cookies.isNullOrBlank()) {
                            addHeader("Cookie", cookies)
                            break
                        }
                    }
                } catch (_: Exception) {}

                header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            }.build()

            val resp = http.newCall(req).execute()
            val mime = resp.header("Content-Type", "") ?: ""
            val size = resp.header("Content-Length", "-1")?.toLongOrNull() ?: -1L
            resp.close()

            val mimeLower = mime.lowercase()

            val resolvedType = when {
                VIDEO_MIME_PREFIXES.any { mimeLower.startsWith(it) } -> {
                    if (mimeLower.contains("mpegurl")) MediaType.HLS
                    else if (mimeLower.contains("dash")) MediaType.DASH
                    else MediaType.VIDEO
                }
                AUDIO_MIME_PREFIXES.any { mimeLower.startsWith(it) } -> MediaType.AUDIO
                // application/octet-stream with a media-looking URL is likely video
                mimeLower in EXTRA_MEDIA_MIME && looksLikeMedia(url) -> MediaType.VIDEO
                hintType != MediaType.UNKNOWN -> hintType
                else -> return   // not media
            }

            emit(buildItem(url, mime.substringAfter('/').substringBefore(';'),
                resolvedType, headers, size, pageUrl, pageTitle))
        } catch (e: Exception) {
            // Network error — if hint says VIDEO/AUDIO, still emit
            if (hintType == MediaType.VIDEO || hintType == MediaType.AUDIO) {
                emit(buildItem(url, "", hintType, headers, -1L, pageUrl, pageTitle))
            }
        }
    }

    /**
     * Extract video URLs from response body content (JSON, HTML, plain text)
     * using regex patterns.
     */
    private fun extractVideoUrlsFromContent(
        sourceUrl: String, content: String,
        pageUrl: String, pageTitle: String
    ) {
        for (pattern in VIDEO_URL_PATTERNS) {
            try {
                pattern.findAll(content).forEach { match ->
                    val extractedUrl = match.groupValues.getOrNull(1) ?: return@forEach
                    if (extractedUrl.length > 15 && extractedUrl.startsWith("http")) {
                        // Validate: URL must have a recognized media extension or path
                        val lower = extractedUrl.lowercase()
                        val isMedia = lower.contains(".m3u8") || lower.contains(".mpd") ||
                                     lower.contains(".mp4") || lower.contains(".webm") ||
                                     lower.contains(".mkv") || lower.contains(".ts") ||
                                     lower.contains("/video/") || lower.contains("/stream/") ||
                                     lower.contains("/media/") || lower.contains("/hls/")
                        if (isMedia) {
                            analyseUrl(extractedUrl, emptyMap(), pageUrl, pageTitle)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildItem(
        url: String, extOrMime: String, type: MediaType,
        headers: Map<String, String>, size: Long,
        pageUrl: String, pageTitle: String
    ): MediaItem {
        val quality = guessQuality(url, headers)

        // For HLS/DASH streams: use page title as filename
        val safeName = if (type == MediaType.HLS || type == MediaType.DASH) {
            val cleanTitle = pageTitle
                .replace(Regex("[\\/:*?\"<>|]+"), " ")
                .replace(Regex("\\s+"), "_")
                .replace(Regex("-{2,}"), "-")
                .trim('_', '-', ' ')
            if (cleanTitle.length >= 3) {
                val qualitySuffix = when (quality) {
                    MediaQuality.ULTRA -> "_4K"
                    MediaQuality.HIGH -> "_1080p"
                    MediaQuality.MEDIUM -> "_720p"
                    else -> ""
                }
                "${cleanTitle.take(80)}${qualitySuffix}.ts"
            } else {
                "hitif_${System.currentTimeMillis() / 1000}.ts"
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
