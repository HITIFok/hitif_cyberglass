package com.hitif.videodownloader.ui

import java.util.UUID

/**
 * Represents a browser tab.
 * Each tab stores its URL and title.
 * The single WebView loads the URL when switching tabs.
 */
data class Tab(
    val id: String = UUID.randomUUID().toString().take(8),
    var url: String = "https://www.google.com",
    var title: String = "Nouvel onglet"
)
