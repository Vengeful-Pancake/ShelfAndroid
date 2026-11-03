package com.shelf.reader.ui.browser

import java.net.URI

internal object WebNavigationPolicy {
    // WebView only supports "*" as an all-host wildcard, not "https://*".
    // The bridge handles arbitrary novel websites; every callback is still
    // restricted to the current main HTTPS origin before its data is read.
    val translationOriginRules = setOf("*")

    fun acceptsTranslationOrigin(source: String, currentPage: String?, mainFrame: Boolean): Boolean = runCatching {
        if (!mainFrame || currentPage == null || !isSecureWebUrl(source) || !isSecureWebUrl(currentPage)) return false
        val origin = URI(source)
        val current = URI(currentPage)
        origin.host.equals(current.host, true) &&
            (if (origin.port == -1) 443 else origin.port) == (if (current.port == -1) 443 else current.port)
    }.getOrDefault(false)

    fun isSecureWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    fun mayOpenExternal(value: String, mainFrame: Boolean, userGesture: Boolean): Boolean =
        mainFrame && userGesture && runCatching {
            URI(value).scheme.lowercase() in setOf("mailto", "tel")
        }.getOrDefault(false)
}
