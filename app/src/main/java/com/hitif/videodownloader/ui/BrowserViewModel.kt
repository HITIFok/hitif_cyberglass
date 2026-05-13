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
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.util.regex.Pattern

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

    // ── Navigation-based season tracking ─────────────────────────────────────
    private val _navEpisodes = mutableMapOf<String, MutableSet<Int>>()

    val detector = MediaDetector { item ->
        val key = item.url.substringBefore('?')
        synchronized(_seenUrls) {
            if (_seenUrls.add(key)) {
                val current = mediaItems.value.orEmpty().toMutableList()
                current.add(0, item)
                mediaItems.postValue(current)
                recomputeSeasonGroups(current)
            }
        }
    }

    /** Called when the WebView navigates to a new page */
    fun onPageNavigated(url: String, title: String) {
        pageUrl.postValue(url)
        pageTitle.postValue(title)
        trackNavigationEpisode(url)
    }

    private fun trackNavigationEpisode(url: String) {
        try {
            val decoded = URLDecoder.decode(url, "UTF-8")
            val epPattern = Pattern.compile(
                """(?:episode|épisode|ep)[\s\-–.]*(\d{1,3})""",
                Pattern.CASE_INSENSITIVE
            )
            val matcher = epPattern.matcher(decoded)
            if (matcher.find()) {
                val epNum = matcher.group(1)?.toIntOrNull() ?: return
                val seriesKey = decoded
                    .replace(Regex("""/episode[\s\-–.]?\d{1,3}.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""/ep[\s\-–.]?\d{1,3}.*$""", RegexOption.IGNORE_CASE), "")
                    .substringBefore('?').substringBefore('#').trimEnd('/')

                if (seriesKey.length >= 5) {
                    val episodes = _navEpisodes.getOrPut(seriesKey) { mutableSetOf() }
                    episodes.add(epNum)
                    if (episodes.size >= 2) {
                        buildNavigationSeasonCandidates(seriesKey, url)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun buildNavigationSeasonCandidates(seriesKey: String, currentUrl: String) {
        val episodes = _navEpisodes[seriesKey] ?: return
        val sorted = episodes.sorted()
        val seriesName = seriesKey.substringAfterLast('/').replace(Regex("[_\\-]+"), " ").trim()

        val candidates = sorted.map { ep ->
            val epUrl = "$seriesKey/episode-$ep.html"
            val item = MediaItem(
                url = epUrl,
                filename = "${seriesName.replace(" ", "_")}_E${ep.toString().padStart(2, '0')}.mp4",
                pageUrl = epUrl,
                mediaType = MediaType.HLS
            )
            SmartNaming.EpisodeCandidate(
                item = item,
                seriesName = seriesName,
                season = 0,
                episode = ep,
                label = "E${ep.toString().padStart(2, '0')}"
            )
        }

        val existing = seasonGroups.value.orEmpty().toMutableMap()
        existing[seriesKey.lowercase().trim()] = candidates
        seasonGroups.postValue(existing)
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
        viewModelScope.launch { db.downloadDao().deleteFailedRecords() }
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
