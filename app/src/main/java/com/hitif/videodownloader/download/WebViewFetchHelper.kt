package com.hitif.videodownloader.download

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fetches URL content through the main WebView's JavaScript engine.
 *
 * This is the KEY fix for "Unable to resolve host" errors on proxy hostnames
 * (e.g. prx-*.vmwesa.online). These hostnames use private DNS that only
 * Chrome's resolver (inside WebView) can resolve. OkHttp uses the system DNS
 * which cannot resolve them — even with DNS-over-HTTPS fallback.
 *
 * How it works:
 *   1. We inject JavaScript XHR into the WebView (same origin, same cookies)
 *   2. Chrome's network stack resolves the DNS and fetches the content
 *   3. The content is passed back via @JavascriptInterface
 *   4. We return it to the caller for native parsing and segment downloading
 *
 * For segments: after the m3u8 is fetched via XHR, Chrome has cached the DNS
 * for the proxy host. We use a hidden iframe trick to "warm" the DNS cache
 * before OkHttp tries to download segments.
 */
object WebViewFetchHelper {

    private const val TAG = "WebViewFetch"
    private const val FETCH_TIMEOUT_MS = 20_000L

    // Pending fetch requests: url -> deferred result
    private val pendingFetches = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    // Reference to the main WebView (set from BrowserActivity)
    @Volatile
    var webView: WebView? = null

    // ========================================================================
    // JavascriptInterface callback — called from JavaScript
    // ========================================================================

    /**
     * Registered as @JavascriptInterface so JavaScript can call it.
     * Must be public and on a stable class (object).
     */
    @JvmStatic
    @JavascriptInterface
    fun onFetchResult(url: String, content: String) {
        Log.d(TAG, "Fetch result for ${url.take(80)}: ${content.length} chars")
        pendingFetches.remove(url)?.complete(content.ifBlank { null })
    }

    @JvmStatic
    @JavascriptInterface
    fun onFetchError(url: String, error: String) {
        Log.w(TAG, "Fetch error for ${url.take(80)}: $error")
        pendingFetches.remove(url)?.complete(null)
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Fetch URL content through the WebView's JavaScript XHR.
     * Returns null if the fetch fails (CORS, network error, timeout).
     *
     * MUST be called from a coroutine with access to Main thread.
     */
    suspend fun fetchContent(url: String, referer: String = ""): String? {
        val wv = webView ?: run {
            Log.w(TAG, "No WebView set, cannot fetch via JS")
            return null
        }

        val deferred = CompletableDeferred<String?>()
        pendingFetches[url] = deferred

        try {
            // Build JavaScript that fetches the URL via XHR
            val encodedUrl = url.replace("\\", "\\\\").replace("'", "\\'")
            val encodedReferer = referer.replace("\\", "\\\\").replace("'", "\\'")

            val js = """
            (function() {
                try {
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', '$encodedUrl', true);
                    xhr.responseType = 'text';
                    xhr.timeout = ${FETCH_TIMEOUT_MS};
                    ${if (encodedReferer.isNotBlank()) "xhr.setRequestHeader('Referer', '$encodedReferer');" else ""}
                    xhr.onload = function() {
                        if (xhr.status >= 200 && xhr.status < 400) {
                            try {
                                window.HitifFetchBridge.onFetchResult('$encodedUrl', xhr.responseText);
                            } catch(e) {
                                try { window.HitifFetchBridge.onFetchError('$encodedUrl', 'bridge error'); } catch(e2) {}
                            }
                        } else {
                            try { window.HitifFetchBridge.onFetchError('$encodedUrl', 'HTTP ' + xhr.status); } catch(e) {}
                        }
                    };
                    xhr.onerror = function() {
                        try { window.HitifFetchBridge.onFetchError('$encodedUrl', 'network error'); } catch(e) {}
                    };
                    xhr.ontimeout = function() {
                        try { window.HitifFetchBridge.onFetchError('$encodedUrl', 'timeout'); } catch(e) {}
                    };
                    xhr.send();
                } catch(e) {
                    try { window.HitifFetchBridge.onFetchError('$encodedUrl', 'xhr exception: ' + e.message); } catch(e2) {}
                }
            })();
            """.trimIndent()

            withContext(Dispatchers.Main) {
                try {
                    wv.evaluateJavascript(js, null)
                } catch (e: Exception) {
                    Log.e(TAG, "evaluateJavascript failed: ${e.message}")
                    pendingFetches.remove(url)?.complete(null)
                }
            }

            // Wait for result with timeout
            return kotlinx.coroutines.withTimeout(TimeUnit.MILLISECONDS.toMillis(FETCH_TIMEOUT_MS + 5000)) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Fetch timeout for ${url.take(80)}")
            pendingFetches.remove(url)?.cancel()
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for ${url.take(80)}: ${e.message}")
            pendingFetches.remove(url)?.cancel()
            return null
        }
    }

    /**
     * Warm up Chrome's DNS cache for a hostname by loading a tiny request.
     * This helps OkHttp resolve the hostname after Chrome has cached the DNS.
     *
     * Uses an Image object to trigger DNS resolution without downloading content.
     */
    suspend fun warmDns(url: String) {
        val wv = webView ?: return
        val host = try {
            java.net.URL(url).host
        } catch (_: Exception) {
            return
        }

        val js = """
        (function() {
            try {
                var img = new Image();
                img.src = 'https://$host/favicon.ico?_t=' + Date.now();
                setTimeout(function() { img.src = ''; }, 3000);
            } catch(e) {}
        })();
        """.trimIndent()

        try {
            withContext(Dispatchers.Main) {
                wv.evaluateJavascript(js, null)
            }
            // Wait a bit for DNS resolution
            kotlinx.coroutines.delay(1500)
        } catch (_: Exception) {}
    }
}
