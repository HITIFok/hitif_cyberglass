package com.hitif.videodownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.hitif.videodownloader.db.AppDatabase
import com.hitif.videodownloader.db.DownloadRecord
import com.hitif.videodownloader.db.FavoriteSite
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType
import com.hitif.videodownloader.network.MediaDetector
import com.hitif.videodownloader.network.SmartNaming
import com.hitif.videodownloader.download.DownloadNotificationManager
import com.hitif.videodownloader.download.YouTubeExtractor
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.launch

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    companion object { const val HOME_URL = "https://www.google.com" }

    // ── Browser state ────────────────────────────────────────────────────────
    val mediaItems   = MutableLiveData<List<MediaItem>>(emptyList())
    val pageTitle    = MutableLiveData<String>("")
    val pageUrl      = MutableLiveData<String>("")
    val isLoading    = MutableLiveData<Boolean>(false)
    val canGoBack    = MutableLiveData<Boolean>(false)
    val canGoForward = MutableLiveData<Boolean>(false)

    // ── Season candidates (updated whenever mediaItems changes) ──────────────
    val seasonGroups = MutableLiveData<Map<String, List<SmartNaming.EpisodeCandidate>>>(emptyMap())

    // ── Turbo mode toggle ────────────────────────────────────────────────────
    val turboMode = MutableLiveData<Boolean>(false)

    // ── Download history from Room ───────────────────────────────────────────
    private val db: AppDatabase by lazy { AppDatabase.getInstance(app) }
    val downloadHistory: LiveData<List<DownloadRecord>> = db.downloadDao().observeAll()

    // ── Favorites from Room ──────────────────────────────────────────────────
    val favorites: LiveData<List<FavoriteSite>> = db.favoriteDao().observeAll()
    val isFavorite = MutableLiveData<Boolean>(false)

    private val _seenUrls = mutableSetOf<String>()

    /**
     * Base URL of the last page where media was detected.
     * Used to accumulate media across episode pages of the same series.
     * e.g. "/anime-vf/2703-trigun/" stays the same across episode-1, episode-2, etc.
     */
    private var lastMediaBase: String = ""

    val detector = MediaDetector { item ->
        // Dedup key: for YouTube page URLs, include the video ID.
        // "youtube.com/watch?v=abc" and "youtube.com/watch?v=xyz" must not collide.
        val key = if (item.url.contains("youtube.com/watch") ||
                     item.url.contains("youtube.com/shorts/") ||
                     item.url.contains("youtu.be/")) {
            val videoIdMatch = Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(item.url)
            if (videoIdMatch != null) "yt:${videoIdMatch.groupValues[1]}"
            else item.url.substringBefore('?').substringBefore('#')
        } else {
            item.url.substringBefore('?').substringBefore('#')
        }
        synchronized(_seenUrls) {
            if (_seenUrls.add(key)) {
                val current = mediaItems.value.orEmpty().toMutableList()
                current.add(0, item)
                // Use setValue (synchronous) instead of postValue (async) to prevent
                // race conditions with clearMedia()'s setValue(emptyList).
                // postValue can be silently dropped if a setValue follows before delivery.
                mediaItems.value = current
                recomputeSeasonGroups(current)
            }
        }
    }

    /** Called when the WebView navigates to a new page */
    fun onPageNavigated(url: String, title: String) {
        pageUrl.postValue(url)
        pageTitle.postValue(title)
    }

    /**
     * Smart media clearing: only clears detected media when navigating to
     * a DIFFERENT series/site. Keeps media when navigating between episodes
     * of the same anime (same base path).
     *
     * IMPORTANT — YouTube subdomain protection:
     * YouTube navigates between subdomains during the same session:
     *   www.youtube.com → consent.youtube.com → accounts.youtube.com → www.youtube.com
     * Without protection, clearMedia() would wipe the detected YouTube video item
     * during these intermediate redirects. We preserve YouTube page URL items
     * across subdomain changes to prevent this.
     *
     * Example:
     *  episode-1.html → episode-2.html        → KEEP media (same base)
     *  episode-2.html → google.com              → CLEAR media (different base)
     *  youtube.com/watch → consent.youtube.com  → KEEP YouTube items (subdomain change)
     */
    fun clearMediaIfNeeded(url: String) {
        val newBase = extractSeriesBase(url)
        if (newBase != lastMediaBase && lastMediaBase.isNotEmpty()) {
            // Check if both URLs are YouTube subdomains — don't clear in that case
            if (isYouTubeSubdomainNavigation(newBase, lastMediaBase)) {
                return
            }
            // Navigating to a different series/site — clear everything
            clearMedia()
        }
        // Update base even if we didn't clear — next navigation will compare against this
        // (we set it after onPageFinished so the current page's media gets detected first)
    }

    /**
     * Detect if a navigation between two series bases is just a YouTube subdomain
     * change (e.g. www.youtube.com → consent.youtube.com → accounts.youtube.com).
     * These should NOT trigger clearMedia() because the YouTube video item would be lost.
     */
    private fun isYouTubeSubdomainNavigation(newBase: String, oldBase: String): Boolean {
        val newYt = isYouTubeHost(newBase)
        val oldYt = isYouTubeHost(oldBase)
        return newYt && oldYt
    }

    private fun isYouTubeHost(base: String): Boolean {
        val ytHosts = listOf(
            "youtube.com", "youtu.be",
            "www.youtube.com", "m.youtube.com",
            "consent.youtube.com", "accounts.youtube.com",
            "music.youtube.com", "studio.youtube.com"
        )
        val host = base.substringBefore('/')
        return ytHosts.any { host.contains(it) || it.contains(host) }
    }

    /** Call this from onPageFinished so the base is set AFTER media detection starts */
    fun updateMediaBase(url: String) {
        val newBase = extractSeriesBase(url)
        if (newBase.isNotEmpty()) {
            lastMediaBase = newBase
        }
    }

    /**
     * Extract the "series base" from a URL — the parent directory of an episode page.
     * e.g. "https://ww.animesultra.org/anime-vf/2703-trigun/episode-1.html"
     *   → "ww.animesultra.org/anime-vf/2703-trigun"
     *
     * For non-episode URLs, returns the host + first 2 path segments.
     */
    private fun extractSeriesBase(url: String): String {
        try {
            val withoutQuery = url.substringBefore('?').substringBefore('#').trimEnd('/')
            val host = withoutQuery.substringAfter("://").substringBefore('/')

            // Remove episode-like suffix: /episode-1, /ep-2, /saison-1, etc.
            val path = withoutQuery.substringAfter("://").substringAfter('/')
            val stripped = path.replace(
                Regex("""/(episode|ep|saison|season)[\s\-–.]?\d{1,3}.*$""", RegexOption.IGNORE_CASE),
                ""
            ).trimEnd('/')

            // Also strip trailing /0-... common in some anime sites
            val clean = stripped.replace(
                Regex("""/\d{2,}[\-–].*$"""),
                ""
            ).trimEnd('/')

            return if (clean.length >= host.length) {
                "$host/$clean"
            } else {
                host
            }
        } catch (_: Exception) {
            return url.substringBefore('?').substringBefore('/')
        }
    }

    private fun recomputeSeasonGroups(currentItems: List<MediaItem>) {
        val fromMedia = SmartNaming.detectSeasonCandidates(currentItems)
        val merged = fromMedia.toMutableMap()
        for ((key, candidates) in seasonGroups.value.orEmpty()) {
            if (key !in merged) merged[key] = candidates
        }
        seasonGroups.postValue(merged)
    }

    fun clearMedia() {
        _seenUrls.clear()
        detector.reset()
        mediaItems.value = emptyList()
        recomputeSeasonGroups(emptyList())
    }

    /**
     * Remove stale YouTube (googlevideo.com) URLs from the media list.
     * Called by the 60s refresh timer before re-parsing formats.
     * This prevents expired URLs from accumulating in the UI.
     */
    fun removeStaleYoutubeUrls() {
        synchronized(_seenUrls) {
            // Remove from _seenUrls dedup set
            _seenUrls.removeAll { it.contains("googlevideo.com") }

            // Remove from media list (post new list without stale URLs)
            val current = mediaItems.value.orEmpty()
            val filtered = current.filter { !it.url.contains("googlevideo.com") }
            if (filtered.size != current.size) {
                mediaItems.postValue(filtered)
                recomputeSeasonGroups(filtered)
            }
        }
        // Also clear from detector's internal dedup
        detector.removeStaleYoutubeUrls()
    }

    fun removeItem(item: MediaItem) {
        val list = mediaItems.value.orEmpty().toMutableList()
        list.remove(item)
        mediaItems.value = list
        recomputeSeasonGroups(list)
    }

    fun mediaCount(): Int = mediaItems.value?.size ?: 0
    fun hasSeasons(): Boolean = seasonGroups.value?.isNotEmpty() == true

    // ── History operations ───────────────────────────────────────────────────

    fun deleteHistoryRecord(record: DownloadRecord) {
        viewModelScope.launch { db.downloadDao().delete(record) }
    }

    fun clearHistory() {
        viewModelScope.launch { db.downloadDao().deleteAll() }
    }

    fun clearFailedHistory() {
        viewModelScope.launch {
            // Dismiss error notifications for failed records before deleting them
            try {
                val failedRecords = db.downloadDao().getRecent(500)
                    .filter { it.state == "FAILED" }
                failedRecords.forEach { record ->
                    DownloadNotificationManager.dismiss(record.url)
                }
            } catch (_: Exception) {}
            db.downloadDao().deleteFailedRecords()
        }
    }

    // ── Favorites operations ──────────────────────────────────────────────────

    /** Check if current URL is a favorite (call from IO scope) */
    fun checkFavorite(url: String) {
        viewModelScope.launch {
            val exists = db.favoriteDao().exists(url)
            isFavorite.postValue(exists)
        }
    }

    /** Toggle favorite: add if not exists, remove if exists */
    fun toggleFavorite(url: String, title: String) {
        viewModelScope.launch {
            val exists = db.favoriteDao().exists(url)
            if (exists) {
                db.favoriteDao().deleteByUrl(url)
                isFavorite.postValue(false)
            } else {
                db.favoriteDao().insert(FavoriteSite(url = url, title = title))
                isFavorite.postValue(true)
            }
        }
    }

    fun deleteFavorite(fav: FavoriteSite) {
        viewModelScope.launch { db.favoriteDao().delete(fav) }
    }

    /** Called from FavoritesFragment to navigate to a favorite URL */
    private val _navigateToUrl = MutableLiveData<String?>()
    val navigateToUrl: LiveData<String?> = _navigateToUrl

    fun openFavoriteUrl(url: String) {
        _navigateToUrl.value = url
    }

    fun clearNavigation() {
        _navigateToUrl.value = null
    }

    // ── Tab management ────────────────────────────────────────────────────────

    private val _tabs = mutableListOf<Tab>()
    val tabs: List<Tab> get() = _tabs.toList()

    private val _tabsLiveData = MutableLiveData<List<Tab>>(emptyList())
    val tabsLiveData: LiveData<List<Tab>> = _tabsLiveData

    var currentTabId: String? = null
        private set

    /** Initialize the first tab on app start */
    fun initFirstTab(url: String) {
        if (_tabs.isEmpty()) {
            val tab = Tab(url = url, title = HOME_URL)
            _tabs.add(tab)
            currentTabId = tab.id
            refreshTabsLiveData()
        }
    }

    /** Create a new tab and return its URL */
    fun newTab(url: String = HOME_URL): Tab {
        saveCurrentTab()
        val tab = Tab(url = url, title = "Nouvel onglet")
        _tabs.add(tab)
        currentTabId = tab.id
        refreshTabsLiveData()
        return tab
    }

    /** Switch to a different tab by id, returns the URL to load */
    fun switchToTab(tabId: String): String? {
        val tab = _tabs.find { it.id == tabId } ?: return null
        if (tabId == currentTabId) return null
        saveCurrentTab()
        currentTabId = tab.id
        refreshTabsLiveData()
        return tab.url
    }

    /** Close a tab by id, returns the URL of the tab to switch to (if any) */
    fun closeTab(tabId: String): TabSwitchResult {
        val index = _tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return TabSwitchResult(null, _tabs.firstOrNull()?.url ?: HOME_URL)

        _tabs.removeAt(index)

        // If we closed the current tab, switch to the nearest one
        if (tabId == currentTabId) {
            if (_tabs.isEmpty()) {
                // No tabs left: create a new one
                val newTab = Tab(url = HOME_URL, title = "Nouvel onglet")
                _tabs.add(newTab)
                currentTabId = newTab.id
                refreshTabsLiveData()
                return TabSwitchResult(newTab.url, newTab.url)
            } else {
                val newIndex = index.coerceAtMost(_tabs.size - 1)
                val nextTab = _tabs[newIndex]
                currentTabId = nextTab.id
                refreshTabsLiveData()
                return TabSwitchResult(nextTab.url, nextTab.url)
            }
        }

        refreshTabsLiveData()
        return TabSwitchResult(null, _tabs.firstOrNull()?.url ?: HOME_URL)
    }

    /** Update current tab's URL and title (called from onPageFinished) */
    fun updateCurrentTab(url: String, title: String) {
        val tab = _tabs.find { it.id == currentTabId } ?: return
        tab.url = url
        tab.title = title.ifBlank { url }
    }

    /** Save the current URL to the active tab */
    private fun saveCurrentTab() {
        val currentUrl = pageUrl.value ?: return
        val currentTitle = pageTitle.value ?: ""
        val tab = _tabs.find { it.id == currentTabId } ?: return
        tab.url = currentUrl
        tab.title = currentTitle.ifBlank { currentUrl }
    }

    private fun refreshTabsLiveData() {
        _tabsLiveData.postValue(_tabs.toList())
    }
}

/** Result of a tab close operation */
data class TabSwitchResult(
    val newUrl: String?,       // URL to load in WebView (null if current tab unchanged)
    val fallbackUrl: String    // URL to load if no tabs remain
)
