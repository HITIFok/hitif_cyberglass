package com.hitif.videodownloader.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.hitif.videodownloader.R
import com.hitif.videodownloader.databinding.ActivityBrowserBinding
import com.hitif.videodownloader.download.WebViewFetchHelper
import com.hitif.videodownloader.network.JsBridge
import com.hitif.videodownloader.network.JsInterface

class BrowserActivity : AppCompatActivity(), TabSwitcherListener {

    private lateinit var binding: ActivityBrowserBinding
    private val vm: BrowserViewModel by viewModels()

    companion object { const val HOME_URL = "https://www.google.com" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestStoragePermission()
        setupWebView()
        setupAddressBar()
        setupToolbar()
        setupObservers()
        setupBackPress()

        val url = intent?.data?.toString()
            ?: savedInstanceState?.getString("url")
            ?: HOME_URL

        // Initialize first tab
        vm.initFirstTab(url)
        binding.webView.loadUrl(url)
    }

    // ── WebView ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        wv.addJavascriptInterface(
            JsInterface(vm.detector, vm.pageUrl, vm.pageTitle),
            JsBridge.INTERFACE_NAME
        )

        // Register WebViewFetchHelper bridge for m3u8 content fetching
        wv.addJavascriptInterface(
            WebViewFetchHelper,
            "HitifFetchBridge"
        )

        // Store WebView reference for fetch helper
        WebViewFetchHelper.webView = wv

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                vm.detector.analyse(request, vm.pageUrl.value ?: "", vm.pageTitle.value ?: "")
                return null
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                vm.isLoading.postValue(true)
                vm.clearMedia()
                binding.addressBar.setText(url)
                binding.progressBar.isVisible = true
            }
            override fun onPageFinished(view: WebView, url: String) {
                vm.isLoading.postValue(false)
                vm.canGoBack.postValue(view.canGoBack())
                vm.canGoForward.postValue(view.canGoForward())
                binding.progressBar.isVisible = false
                val title = view.title ?: ""
                vm.onPageNavigated(url, title)
                vm.updateCurrentTab(url, title)
                view.evaluateJavascript(JsBridge.INJECT_SCRIPT, null)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    vm.isLoading.postValue(false)
                    binding.progressBar.isVisible = false
                }
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = handleUrl(url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                handleUrl(request.url.toString())
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {
                vm.pageTitle.postValue(title)
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.progress = newProgress
            }
        }
    }

    private fun handleUrl(url: String): Boolean {
        if (url.startsWith("http")) return false
        return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
        catch (_: Exception) { true }
    }

    // ── Address bar ──────────────────────────────────────────────────────────

    private fun setupAddressBar() {
        binding.addressBar.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigate(v.text.toString()); true
            } else false
        }
        binding.btnGo.setOnClickListener { navigate(binding.addressBar.text.toString()) }
        binding.addressBar.setOnFocusChangeListener { _, focused ->
            if (focused) binding.addressBar.selectAll()
        }
    }

    private fun navigate(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains('.') && !input.contains(' ') -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }
        binding.webView.loadUrl(url)
        binding.addressBar.clearFocus()
    }

    // ── Bottom toolbar ───────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener    { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.btnBack.setOnLongClickListener { binding.webView.loadUrl(HOME_URL); true }
        binding.btnForward.setOnClickListener  { if (binding.webView.canGoForward()) binding.webView.goForward() }
        binding.btnRefresh.setOnClickListener  {
            if (vm.isLoading.value == true) binding.webView.stopLoading()
            else binding.webView.reload()
        }
        binding.btnHome.setOnClickListener    { binding.webView.loadUrl(HOME_URL) }
        binding.btnNewTab.setOnClickListener { openTabSwitcher() }
        binding.btnMedia.setOnClickListener   { openMediaPanel() }
        binding.btnSeason.setOnClickListener { openSeasonDialog() }
        binding.btnHistory.setOnClickListener { openHistory() }
        binding.btnFavorites.setOnClickListener { openFavorites() }

        // Star button in address bar: toggle favorite for current page
        binding.btnFavorite.setOnClickListener {
            val url = binding.webView.url ?: return@setOnClickListener
            val title = vm.pageTitle.value ?: url
            vm.toggleFavorite(url, title)
        }
    }

    private fun openMediaPanel() {
        if (supportFragmentManager.findFragmentByTag("media_panel") != null) return
        MediaPanelFragment().show(supportFragmentManager, "media_panel")
    }

    private fun openHistory() {
        if (supportFragmentManager.findFragmentByTag("history") != null) return
        HistoryFragment().show(supportFragmentManager, "history")
    }

    private fun openFavorites() {
        if (supportFragmentManager.findFragmentByTag("favorites") != null) return
        FavoritesFragment().show(supportFragmentManager, "favorites")
    }

    private fun openTabSwitcher() {
        if (supportFragmentManager.findFragmentByTag("tab_switcher") != null) return
        TabSwitcherFragment().show(supportFragmentManager, "tab_switcher")
    }

    // ── TabSwitcherListener ───────────────────────────────────────────────────

    override fun onTabSwitched(tab: Tab) {
        val url = vm.switchToTab(tab.id) ?: return
        vm.clearMedia()
        binding.addressBar.setText(url)
        binding.webView.loadUrl(url)
    }

    override fun onTabSwitchedResult(result: TabSwitchResult) {
        val url = result.newUrl ?: return
        vm.clearMedia()
        binding.addressBar.setText(url)
        binding.webView.loadUrl(url)
    }

    override fun onNewTabRequested() {
        val tab = vm.newTab()
        vm.clearMedia()
        binding.addressBar.setText(tab.url)
        binding.webView.loadUrl(tab.url)
    }

    private fun openSeasonDialog() {
        if (supportFragmentManager.findFragmentByTag("season_dialog") != null) return
        val groups = vm.seasonGroups.value ?: return
        val firstKey = groups.keys.firstOrNull() ?: return
        SeasonDialogFragment.newInstance(firstKey).show(supportFragmentManager, "season_dialog")
    }

    // ── Observers ────────────────────────────────────────────────────────────

    private fun setupObservers() {
        vm.isLoading.observe(this) { loading ->
            binding.btnRefresh.setImageResource(
                if (loading) R.drawable.ic_stop else R.drawable.ic_refresh
            )
        }
        vm.canGoBack.observe(this)    { binding.btnBack.alpha    = if (it) 1f else 0.35f }
        vm.canGoForward.observe(this) { binding.btnForward.alpha = if (it) 1f else 0.35f }

        vm.mediaItems.observe(this) { items ->
            val count = items.size
            binding.btnMediaBadge.text = if (count > 0) count.toString() else ""
            binding.btnMediaBadge.isVisible = count > 0
            if (count > 0) pulseButton(binding.btnMedia)
        }

        vm.seasonGroups.observe(this) { groups ->
            // Show season FAB if season groups detected
            binding.btnSeason.isVisible = groups.isNotEmpty()
        }

        vm.downloadHistory.observe(this) { records ->
            val done = records.count { it.state == "COMPLETED" }
            binding.btnHistoryBadge.text = if (done > 0) done.toString() else ""
            binding.btnHistoryBadge.isVisible = done > 0
        }

        // Update favorite star icon when page changes
        vm.pageUrl.observe(this) { url ->
            vm.checkFavorite(url)
        }

        vm.isFavorite.observe(this) { isFav ->
            binding.btnFavorite.setImageResource(
                if (isFav) R.drawable.ic_star else R.drawable.ic_star_outline
            )
        }

        // Show favorites count badge
        vm.favorites.observe(this) { list ->
            val count = list.size
            binding.btnFavoritesBadge.text = if (count > 0) count.toString() else ""
            binding.btnFavoritesBadge.isVisible = count > 0
        }

        // Navigate when a favorite is opened from FavoritesFragment
        vm.navigateToUrl.observe(this) { url ->
            if (url != null) {
                binding.webView.loadUrl(url)
                vm.clearNavigation()
            }
        }

        // Show tab count badge (always visible when tabs exist)
        vm.tabsLiveData.observe(this) { tabs ->
            val count = tabs.size
            binding.btnTabBadge.text = count.toString()
            binding.btnTabBadge.isVisible = count >= 1
        }
    }

    private fun pulseButton(btn: android.widget.ImageButton) {
        btn.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120)
            .withEndAction { btn.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
            .start()
    }

    // ── Back press ───────────────────────────────────────────────────────────

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val panel = supportFragmentManager.findFragmentByTag("media_panel")
                val hist  = supportFragmentManager.findFragmentByTag("history")
                val fav   = supportFragmentManager.findFragmentByTag("favorites")
                val tabSw = supportFragmentManager.findFragmentByTag("tab_switcher")
                when {
                    tabSw != null -> (tabSw as? TabSwitcherFragment)?.dismiss()
                    panel != null -> (panel as? MediaPanelFragment)?.dismiss()
                    hist != null  -> (hist as? HistoryFragment)?.dismiss()
                    fav != null  -> (fav as? FavoritesFragment)?.dismiss()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ── Storage permissions ──────────────────────────────────────────────────

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this,
                "Permission de stockage requise pour télécharger",
                Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("InlinedApi")
    private fun requestStoragePermission() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: POST_NOTIFICATIONS for download notifications
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 and below: WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Android 11-12: POST_NOTIFICATIONS only (MANAGE_EXTERNAL_STORAGE not needed
            // since we use scoped storage via getExternalStoragePublicDirectory)
        }
        if (permissions.isNotEmpty()) {
            storagePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("url", binding.webView.url)
    }

    override fun onDestroy() {
        WebViewFetchHelper.webView = null
        super.onDestroy()
    }
}
