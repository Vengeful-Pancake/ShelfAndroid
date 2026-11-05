package com.shelf.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.ui.browser.BrowserData
import com.shelf.reader.ui.browser.BrowserStore
import com.shelf.reader.ui.browser.BrowserFavorite
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BrowserFeaturesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var before: BrowserData
    private lateinit var settingsBefore: ReaderSettings
    private val store get() = BrowserStore.get(compose.activity)
    @Before fun prepare() {
        before = store.state.value
        settingsBefore = runBlocking { ShelfApp.repository(compose.activity).settings.current() }
        runBlocking { ShelfApp.repository(compose.activity).settings.update { it.copy(navigationBarVisible = true, navigationBarScale = 1f, browserExtensionsEnabled = true) } }
        store.update { BrowserData() }
    }
    @After fun restore() {
        store.update { before }
        runBlocking { ShelfApp.repository(compose.activity).settings.replace(settingsBefore) }
    }
    @Test fun browserTabsExtensionsHistoryAndFavoritesWorkTogether() {
        compose.onNodeWithText("Internet").performClick()
        val first = web()
        fixture(first, "https://fixture.example/a", "Tab A")
        compose.waitUntil(30_000) { js(first, "document.body.innerText").contains("chào ngươi") }
        js(first, "document.getElementById('draft').value='kept in tab A';window.scrollTo(0,500);true")

        compose.onNodeWithContentDescription("Extensions").performClick()
        compose.onNodeWithContentDescription("Smooth translation extension").performClick()
        compose.waitUntil(10_000) { js(first, "document.body.innerText").contains("你好") }
        js(first, "const p=document.createElement('p');p.id='dynamic';p.textContent='世界';document.body.appendChild(p);true")
        assertEquals("世界", js(first, "document.getElementById('dynamic').innerText"))
        screenshot("browser-extensions-0.3.4.png")
        compose.onNodeWithContentDescription("Smooth translation extension").performClick()
        compose.waitUntil(10_000) { js(first, "document.getElementById('dynamic').innerText") == "thế giới" }
        compose.onNodeWithContentDescription("Close browser panel").performClick()
        compose.onNodeWithContentDescription("Toggle favorite").performClick()
        assertTrue(store.state.value.favorites.any { it.url == "https://fixture.example/a" })

        compose.onNodeWithContentDescription("Browser tabs").performClick()
        compose.onNodeWithText("New tab").performClick()
        compose.waitUntil(10_000) { web() !== first }
        val second = web()
        assertNotSame(first, second)
        fixture(second, "https://fixture.example/b", "Tab B")
        compose.waitUntil(10_000) { store.state.value.tabs.any { it.title == "Tab B" } }
        compose.onNodeWithContentDescription("Browser tabs").performClick()
        screenshot("browser-tabs-0.3.4.png")
        compose.onNodeWithText("Tab A").performClick()
        compose.waitUntil(10_000) { web() === first }
        assertSame(first, web())
        assertEquals("kept in tab A", js(first, "document.getElementById('draft').value"))
        assertTrue(js(first, "window.scrollY").toDouble() > 0)

        compose.onNodeWithContentDescription("Browser menu").performClick()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Tab B").assertExists()
        compose.onNodeWithText("Clear history").performClick()
        compose.onNodeWithText("Clear", substring = false).performClick()
        assertTrue(store.state.value.history.isEmpty())
        assertEquals(1, store.state.value.favorites.size)
        compose.onNodeWithContentDescription("Close browser panel").performClick()
        compose.onNodeWithContentDescription("Browser menu").performClick()
        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithText("Tab A").assertExists()
        compose.onNodeWithContentDescription("Close browser panel").performClick()

        // Serialized data survives creating a fresh screen; each tab also retains its own back stack in memory.
        compose.onNodeWithText("Books").performClick()
        compose.onNodeWithText("Internet").performClick()
        compose.waitUntil(10_000) { val value = AtomicReference(""); val current = web(); compose.runOnUiThread { value.set(current.url) }; value.get() == "https://fixture.example/a" }
        compose.waitUntil(10_000) {
            runCatching {
                val disk = JSONObject(java.io.File(compose.activity.filesDir, "browser.json").readText())
                disk.getJSONArray("tabs").length() == 2 && disk.getJSONArray("favorites").getJSONObject(0).getString("url") == "https://fixture.example/a"
            }.getOrDefault(false)
        }
        compose.onNodeWithContentDescription("Browser tabs").performClick()
        compose.onNodeWithContentDescription("Close tab Tab B").performClick()
        assertEquals(1, store.state.value.tabs.size)
        compose.onNodeWithContentDescription("Close browser panel").performClick()
    }
    @Test fun navigationCanResizeHideAndBeRecovered() {
        val originalHeight = compose.onNodeWithText("Internet").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithContentDescription("Navigation settings").performClick()
        compose.onNodeWithContentDescription("Navigation bar size").performSemanticsAction(SemanticsActions.SetProgress) { it(1.4f) }
        compose.waitUntil(5000) { runBlocking { ShelfApp.repository(compose.activity).settings.current().navigationBarScale } > 1.3f }
        screenshot("navigation-settings-0.3.4.png")
        compose.onNodeWithText("Done").performClick()
        assertTrue(compose.onNodeWithText("Internet").getUnclippedBoundsInRoot().let { it.bottom - it.top } > originalHeight)
        compose.onNodeWithContentDescription("Navigation settings").performClick()
        compose.onNodeWithContentDescription("Show navigation bar").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithContentDescription("Show bottom navigation").assertExists(); true }.getOrDefault(false) }
        compose.onNodeWithText("Internet").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show bottom navigation").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Internet").assertIsDisplayed(); true }.getOrDefault(false) }
    }
    @Test fun browsingDataRestoresAllTabsAndFiltersUnsafeSavedLinks() {
        val data = BrowserData().open("https://fixture.example/b").visit("https://fixture.example/a", "A")
            .copy(favorites = listOf(BrowserFavorite("https://fixture.example/a", "Saved A")))
        val encoded = BrowserStore.encode(data)
        assertEquals(data, BrowserStore.decode(JSONObject(encoded.toString())))
        encoded.getJSONArray("tabs").getJSONObject(1).put("url", "javascript:alert(1)")
        encoded.getJSONArray("favorites").getJSONObject(0).put("url", "file:///data/private")
        val restored = BrowserStore.decode(encoded)
        assertEquals(1, restored.tabs.size); assertEquals(restored.tabs.first().id, restored.activeId)
        assertTrue(restored.favorites.isEmpty())
    }
    private fun web(): WebView {
        val result = AtomicReference<WebView>()
        compose.waitUntil(10_000) { compose.runOnUiThread { result.set(find(compose.activity.window.decorView)) }; result.get() != null }
        return result.get()
    }
    private fun find(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun fixture(web: WebView, url: String, title: String) {
        val html = "<html><head><meta charset='utf-8'><title>$title</title></head><body><input id='draft'><article>" +
            List(30) { "<p>你好，世界！ This is a long novel paragraph for a local browser fixture.</p>" }.joinToString("") + "</article></body></html>"
        compose.runOnUiThread {
            val original = web.webViewClient
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.url.host == "fixture.example") WebResourceResponse("text/html", "UTF-8", html.byteInputStream()) else original.shouldInterceptRequest(view, request)
                override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) = original.onPageStarted(view, url, icon)
                override fun onPageFinished(view: WebView, url: String) = original.onPageFinished(view, url)
            }
            web.loadUrl(url)
        }
    }
    private fun js(web: WebView, script: String): String {
        val result = AtomicReference(""); val done = CountDownLatch(1)
        compose.runOnUiThread { web.evaluateJavascript(script) { result.set(runCatching { JSONTokener(it).nextValue().toString() }.getOrDefault(it)); done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result.get()
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        Thread.sleep(250)
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = automation.takeScreenshot()
        val file = java.io.File(compose.activity.getExternalFilesDir(null), name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name")).use { it.readBytes() }
    }
}
