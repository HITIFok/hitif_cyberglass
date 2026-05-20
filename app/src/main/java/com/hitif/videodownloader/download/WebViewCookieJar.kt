package com.hitif.videodownloader.download

import android.util.Log
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.CookieJar

/**
 * OkHttp CookieJar that bridges Android's WebView CookieManager.
 *
 * When OkHttp follows redirects to different domains (e.g., from the streaming site
 * to a proxy like prx-*.vmwesa.online), this jar ensures that the appropriate cookies
 * are sent. Without this, the proxy server may reject the request with 403 or
 * DNS resolution may fail because the proxy requires session cookies.
 */
class WebViewCookieJar : CookieJar {

    companion object {
        private const val TAG = "WebViewCookieJar"
    }

    private val cookieManager: CookieManager get() = CookieManager.getInstance()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return try {
            val cookieString = cookieManager.getCookie(url.toString())
            if (cookieString.isNullOrBlank()) {
                emptyList()
            } else {
                parseCookies(url, cookieString)
            }
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
