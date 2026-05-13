package com.hitif.videodownloader.network

import android.webkit.JavascriptInterface
import androidx.lifecycle.MutableLiveData
import com.hitif.videodownloader.download.WebViewFetchHelper

/**
 * Native object exposed to JavaScript as window.HITIFAndroid.
 * Receives media URLs discovered by the injected JS script.
 */
class JsInterface(
    private val detector: MediaDetector,
    private val pageUrl: MutableLiveData<String>,
    private val pageTitle: MutableLiveData<String>
) {
    @JavascriptInterface
    fun onMedia(url: String, type: String) {
        if (url.isBlank() || url.length < 10) return
        detector.analyseUrl(
            url  = url,
            pageUrl   = pageUrl.value ?: "",
            pageTitle = pageTitle.value ?: ""
        )
    }
}
