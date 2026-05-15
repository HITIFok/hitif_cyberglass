package com.hitif.videodownloader.network

import android.webkit.JavascriptInterface
import androidx.lifecycle.MutableLiveData
import com.hitif.videodownloader.download.WebViewFetchHelper
import android.util.Log

/**
 * Native object exposed to JavaScript as window.HITIFAndroid.
 * Receives media URLs and response bodies discovered by the injected JS script.
 */
class JsInterface(
    private val detector: MediaDetector,
    private val pageUrl: MutableLiveData<String>,
    private val pageTitle: MutableLiveData<String>
) {
    companion object { const val TAG = "JsInterface" }

    @JavascriptInterface
    fun onMedia(url: String, type: String) {
        if (url.isBlank() || url.length < 10) return
        detector.analyseUrl(
            url  = url,
            pageUrl   = pageUrl.value ?: "",
            pageTitle = pageTitle.value ?: ""
        )
    }

    /** Receives XHR/fetch response body for media content analysis. */
    @JavascriptInterface
    fun onXhrResponse(url: String, body: String, status: Int) {
        if (url.isBlank() || body.isBlank() || body.length < 10) return
        try {
            detector.analyseResponse(
                url      = url,
                content  = body,
                pageUrl  = pageUrl.value ?: "",
                pageTitle = pageTitle.value ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "onXhrResponse error: ${e.message}")
        }
    }
}
