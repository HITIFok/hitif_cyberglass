package com.hitif.videodownloader.network

import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType

/**
 * Intelligent filename generator — mirrors the HITIF Chrome extension's
 * smart naming algorithm.
 *
 * Priority chain:
 *  1. Season/episode pattern detected in page title or URL
 *  2. Page title + format suffix
 *  3. Clean URL segment
 *  4. Timestamp fallback
 */
object SmartNaming {

    // ── Season / episode regexes ─────────────────────────────────────────────

    /** Matches S01E02, s1e2, Season 1 Episode 2, 1x02, etc. */
    private val RE_SE = listOf(
        Regex("""[Ss](\d{1,2})[Ee](\d{1,3})"""),                    // S01E02
        Regex("""[Ss]eason[\s\-–]*(\d{1,2})[\s\-–]*[Ee]pisode[\s\-–]*(\d{1,3})""",
            RegexOption.IGNORE_CASE),                                 // Season 1 Episode 2
        Regex("""(\d{1,2})[xX](\d{1,2})"""),                         // 1x02
        Regex("""[ÉéEe]p(?:isode)?[\s\-–.]*(\d{1,3})"""),              // Épisode 5, episode-1, ep.5, Ep-12
    )

    /** Standalone season without episode */
    private val RE_SEASON_ONLY = Regex("""[Ss](?:aison|eason)\s*(\d{1,2})""", RegexOption.IGNORE_CASE)

    // ── Quality / resolution detection ──────────────────────────────────────

    private val RE_QUALITY = Regex(
        """(4K|2160p?|1080p?|720p?|480p?|360p?|240p?|HDR|HLG|SDR|HD|UHD)""",
        RegexOption.IGNORE_CASE
    )

    // ── Characters forbidden in filenames ───────────────────────────────────

    private val RE_UNSAFE = Regex("""[\\/:*?"<>|]+""")
    private val RE_SPACES  = Regex("""\s+""")
    private val RE_DASHES  = Regex("""-{2,}""")

    // ── Known noise tokens to strip from page titles ─────────────────────────

    private val NOISE = listOf(
        Regex("""[-–|·•]\s*(YouTube|Dailymotion|Vimeo|Netflix|Disney\+?|Prime\s*Video|TF1\+?|France\s*\d+|Arte|MyTF1|6play|Molotov|Pluto\s*TV).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\|\s*.*$"""),                   // everything after " | "
        Regex("""\s*-\s*[^-]{0,30}$"""),            // trailing "- subtitle"
        Regex("""\s*(HD|4K|Full\s*HD|VOSTFR|VF|VO|MKV|AVI|MP4)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(Regarder|Watch|Voir|Lire)\s+""", RegexOption.IGNORE_CASE),
        Regex("""(Streaming|Gratuit|Free|Online)\s*$""", RegexOption.IGNORE_CASE),
    )

    // ────────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────────

    data class NameResult(
        val filename: String,           // full filename with extension
        val baseName: String,           // without extension
        val extension: String,
        val seriesName: String?,
        val season: Int?,
        val episode: Int?,
        val quality: String?
    )

    /**
     * Build a smart filename for [item] given its page context.
     */
    fun build(item: MediaItem): NameResult {
        val ext       = resolveExtension(item)
        val pageTitle = cleanTitle(item.pageTitle)
        val urlSeg    = urlSegment(item.url)

        // 1. Try to find S/E from page title, media URL, then page URL
        val seFromTitle = extractSE(item.pageTitle)
        val seFromUrl   = extractSE(item.url)
        val seFromPage  = extractSE(item.pageUrl)
        val se          = seFromTitle ?: seFromUrl ?: seFromPage

        // 2. Extract quality tag
        val quality = RE_QUALITY.find(item.pageTitle + " " + item.url)
            ?.value?.uppercase()

        // 3. Build base name
        val base: String
        val seriesName: String?
        val season: Int?
        val episode: Int?

        when {
            se != null -> {
                // We have season/episode info
                seriesName = extractSeriesName(item.pageTitle, se.raw)
                    ?: extractSeriesName(item.url.substringAfterLast('/').replace('-', ' '), se.raw)
                    ?: extractSeriesFromPageUrl(item.pageUrl)
                    ?: pageTitle.ifBlank { "Serie" }
                season  = se.season
                episode = se.episode

                val sLabel = if (se.season != null)
                    "S${se.season.toString().padStart(2,'0')}E${se.episode.toString().padStart(2,'0')}"
                else
                    "E${se.episode.toString().padStart(2,'0')}"

                base = buildString {
                    append(sanitize(seriesName))
                    append("_$sLabel")
                    if (quality != null) append("_$quality")
                }
            }

            pageTitle.length >= 4 -> {
                // Use cleaned page title
                seriesName = null; season = null; episode = null
                base = buildString {
                    append(sanitize(pageTitle).take(80))
                    if (quality != null) append("_$quality")
                }
            }

            urlSeg.length >= 4 -> {
                // Fall back to URL segment
                seriesName = null; season = null; episode = null
                base = buildString {
                    append(sanitize(urlSeg).take(60))
                    if (quality != null) append("_$quality")
                }
            }

            else -> {
                // Timestamp fallback
                seriesName = null; season = null; episode = null
                base = "hitif_${System.currentTimeMillis() / 1000}"
            }
        }

        val filename = "${base.take(120)}.$ext"
        return NameResult(filename, base, ext, seriesName, season, episode, quality)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    fun resolveExtension(item: MediaItem): String = when {
        item.mediaType == MediaType.HLS  -> "mp4"
        item.mediaType == MediaType.DASH -> "mp4"
        item.mediaType == MediaType.AUDIO -> {
            val fromUrl = item.url.substringBefore('?').substringAfterLast('.', "")
                .lowercase().take(4)
            if (fromUrl in setOf("mp3","aac","ogg","opus","flac","wav","m4a")) fromUrl else "mp3"
        }
        else -> {
            val fromUrl = item.url.substringBefore('?').substringAfterLast('.', "")
                .lowercase().take(4)
            if (fromUrl in setOf("mp4","webm","mkv","avi","mov","m4v","flv")) fromUrl
            else item.mimeType.substringAfter('/').substringBefore(';').take(4)
                .ifEmpty { "mp4" }
        }
    }

    private data class SEResult(val raw: String, val season: Int?, val episode: Int)

    private fun extractSE(text: String): SEResult? {
        for (re in RE_SE) {
            val m = re.find(text) ?: continue
            return when (m.groupValues.size) {
                3 -> SEResult(m.value, m.groupValues[1].toIntOrNull(), m.groupValues[2].toInt())
                2 -> SEResult(m.value, null, m.groupValues[1].toInt())
                else -> null
            }
        }
        return null
    }

    /** Extract series name = text before the S/E marker (case-insensitive) */
    private fun extractSeriesName(text: String, seRaw: String): String? {
        val lowerText = text.lowercase()
        val lowerRaw = seRaw.lowercase()
        val idx = lowerText.indexOf(lowerRaw)
        if (idx <= 0) return null
        val before = text.substring(0, idx).trim()
            .trimEnd('-', '_', '.', '–', '—', ':', ' ')
        return before.takeIf { it.length >= 2 }
    }

    /** Extract series name from the page URL path (e.g. /2703-trigun-stargaze/episode-1.html) */
    private fun extractSeriesFromPageUrl(pageUrl: String): String? {
        try {
            val path = pageUrl.substringBefore('?').substringBefore('#').trimEnd('/')
            // Remove episode/season suffix from path
            val epPattern = Regex(
                """/(episode|ep|saison|season)[\s\-\–.]?\d{1,3}.*""",
                RegexOption.IGNORE_CASE
            )
            val withoutEp = epPattern.replace(path, "")
            val segments = withoutEp.split('/').filter { it.isNotBlank() }
            if (segments.size >= 2) {
                // Get the last meaningful segment (series slug)
                val slug = segments.last()
                val cleaned = slug
                    .replace(Regex("""^\d{2,}[\-–]"""), "") // strip leading "2703-"
                    .replace(Regex("[-_]+"), " ")
                    .trim()
                return cleaned.takeIf { it.length >= 3 }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
        for (re in NOISE) t = re.replace(t, "")
        return t.trim()
    }

    private fun sanitize(name: String): String =
        name.replace(RE_UNSAFE, " ")
            .replace(RE_SPACES, "_")
            .replace(RE_DASHES, "-")
            .trim('_', '-', ' ')

    private fun urlSegment(url: String): String =
        url.substringBefore('?').substringAfterLast('/')
            .substringBeforeLast('.')
            .replace(Regex("[_\\-]+"), " ")
            .trim()

    // ────────────────────────────────────────────────────────────────────────
    // Season detection — finds episode patterns across a set of URLs/titles
    // ────────────────────────────────────────────────────────────────────────

    data class EpisodeCandidate(
        val item: MediaItem,
        val seriesName: String,
        val season: Int,
        val episode: Int,
        val label: String     // "S01E03"
    )

    /**
     * Given a list of detected MediaItems on the current page,
     * return candidates that look like episodes of the same series.
     *
     * Useful for season-pack download: if ≥ 2 items share the same series name,
     * they are grouped and returned.
     */
    fun detectSeasonCandidates(items: List<MediaItem>): Map<String, List<EpisodeCandidate>> {
        val result = mutableMapOf<String, MutableList<EpisodeCandidate>>()

        for (item in items) {
            val nr = build(item)
            // Must have series name AND episode number
            // Season is optional — default to 1 (common for anime with only episode numbers)
            val ep = nr.episode ?: continue
            val sn = nr.seriesName ?: continue
            val s = nr.season ?: 1

            val key = sn.lowercase().trim()
            val label = if (nr.season != null)
                "S${s.toString().padStart(2,'0')}E${ep.toString().padStart(2,'0')}"
            else
                "E${ep.toString().padStart(2,'0')}"

            result.getOrPut(key) { mutableListOf() }.add(
                EpisodeCandidate(
                    item       = item,
                    seriesName = sn,
                    season     = s,
                    episode    = ep,
                    label      = label
                )
            )
        }

        // Only return groups with ≥ 2 episodes
        return result.filter { it.value.size >= 2 }
            .mapValues { it.value.sortedWith(compareBy({ it.season }, { it.episode })) }
    }
}
