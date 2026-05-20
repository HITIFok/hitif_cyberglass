package com.hitif.videodownloader.download

import android.util.Log
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.CookieJar


/**
 * OkHttp CookieJar that bridges Android's WebView CookieManager.
 *
 * CRITICAL FIX for YouTube/GoogleVideo downloads:
 * When OkHttp requests go to googlevideo.com, the WebView CookieManager
 * only has cookies for that specific domain. YouTube session cookies
 * (from youtube.com — e.g. VISITOR_INFO1_LIVE, CONSENT, LOGIN_INFO)
 * are NOT sent because they belong to a different domain.
 *
 * GoogleVideo CDN REQUIRES these YouTube session cookies for authentication.
 * Without them, every request returns 403 Forbidden.
 *
 * OkHttp's BridgeInterceptor calls loadForRequest() and REPLACES any
 * manually-set Cookie header with the CookieJar's result. So we must
 * include YouTube cookies here — setting them manually in Request headers
 * is useless because BridgeInterceptor overwrites them.
 *
 * Solution: For googlevideo.com requests, merge cookies from:
 *   1. The target domain (googlevideo.com) — from CookieManager
 *   2. youtube.com — from CookieManager
 *   3. .youtube.com (parent domain) — from CookieManager
 *   4. google.com — from CookieManager (for Google auth)
 */
class WebViewCookieJar : CookieJar {

    companion object {
        private const val TAG = "WebViewCookieJar"

        /** Domains whose cookies should be forwarded to googlevideo.com requests */
        private val YOUTUBE_COOKIE_DOMAINS = listOf(
            "https://www.youtube.com/",
            "https://youtube.com/",
            "https://m.youtube.com/",
            "https://www.google.com/"
        )
    }

    private val cookieManager: CookieManager get() = CookieManager.getInstance()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return try {
            val host = url.host
            val allCookies = mutableListOf<Cookie>()

            // 1. Get cookies for the actual request domain
            val directCookies = cookieManager.getCookie(url.toString())
            if (!directCookies.isNullOrBlank()) {
                val parsed = parseCookies(url, directCookies)
                allCookies.addAll(parsed)
                Log.d(TAG, "Direct cookies for $host: ${parsed.size} cookies")
            }

            // 2. For GoogleVideo CDN requests, also include YouTube session cookies
            // This is CRITICAL — without YouTube cookies, googlevideo returns 403
            if (host.contains("googlevideo.com") || host.contains("google.com")) {
                val ytCookieBuilder = StringBuilder()
                val ytCookieNames = mutableSetOf<String>()

                for (cookieDomain in YOUTUBE_COOKIE_DOMAINS) {
                    try {
                        val domainCookies = cookieManager.getCookie(cookieDomain)
                        if (!domainCookies.isNullOrBlank()) {
                            // Parse cookies using the target request URL (already an HttpUrl)
                            // This avoids needing to parse the domain string — all HttpUrl
                            // .get/.parse methods are extensions in OkHttp 4.x
                            val parsed = parseCookies(url, domainCookies)
                            for (cookie in parsed) {
                                if (cookie.name !in ytCookieNames) {
                                    ytCookieNames.add(cookie.name)
                                    allCookies.add(cookie)
                                    if (ytCookieBuilder.isNotEmpty()) ytCookieBuilder.append("; ")
                                    ytCookieBuilder.append("${cookie.name}=${cookie.value}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get cookies from $cookieDomain: ${e.message}")
                    }
                }

                if (ytCookieBuilder.isNotEmpty()) {
                    Log.d(TAG, "YouTube session cookies merged for $host: " +
                        "${ytCookieNames.size} cookies (${ytCookieNames.take(5)}...)")
                }
            }

            if (allCookies.isEmpty()) {
                Log.d(TAG, "No cookies for $host")
            }

            allCookies
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cookies for ${url.host}: ${e.message}")
            emptyList()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        try {
            for (cookie in cookies) {
                val cookieStr = "${cookie.name}=${cookie.value}; path=${cookie.path}"
                cookieManager.setCookie(url.toString(), cookieStr)

                // Also save googlevideo.com cookies to youtube.com context
                // so they're available when we need to forward them back
                if (url.host.contains("googlevideo.com")) {
                    for (ytDomain in YOUTUBE_COOKIE_DOMAINS) {
                        try {
                            cookieManager.setCookie(ytDomain, cookieStr)
                        } catch (_: Exception) {}
                    }
                }
            }
            cookieManager.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cookies for ${url.host}: ${e.message}")
        }
    }

    /**
     * Parse a cookie string like "name1=val1; name2=val2" into OkHttp Cookie objects.
     */
    private fun parseCookies(url: HttpUrl, cookieString: String): List<Cookie> {
        return cookieString.split(";")
            .map { it.trim() }
            .filter { it.contains('=') && it.length > 2 }
            .mapNotNull { segment ->
                try {
                    Cookie.parse(url, segment)
                } catch (_: Exception) {
                    null
                }
            }
    }
}
