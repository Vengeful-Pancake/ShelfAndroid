package com.shelf.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.json.JSONTokener
import org.json.JSONObject
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.shelf.reader.translation.NameStore

class InternetNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var namesBefore: JSONObject
    @Before fun preserveNames() { namesBefore = NameStore.get(compose.activity).snapshot() }
    @After fun restoreNames() { NameStore.get(compose.activity).restore(namesBefore) }

    @Test fun reproducesInvalidOriginRuleException() {
        compose.runOnUiThread {
            val web = WebView(compose.activity)
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    assertThrows(IllegalArgumentException::class.java) {
                        WebViewCompat.addWebMessageListener(web, "Invalid", setOf("https://*")) { _, _, _, _, _ -> }
                    }
                }
            } finally { web.destroy() }
        }
    }

    @Test fun internetTabSurvivesOpeningAndTabSwitches() {
        compose.onNodeWithText("Internet").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Reader").assertExists()
        // Exercise the real browser messaging adapter and offline dictionary,
        // without depending on a remote website's availability or content.
        val web = AtomicReference<WebView>()
        val pageText = AtomicReference("")
        val fixtureHtml = "<html><head><meta charset='utf-8'><title>Fixture novel chapter</title></head><body><article>" +
            List(8) { "<p>你好，世界！ " + "This is a novel paragraph with enough content for the clean reader. ".repeat(4) + "</p>" }.joinToString("") +
            "</article></body></html>"
        compose.runOnUiThread {
            web.set(findWebView(compose.activity.window.decorView) ?: error("Browser WebView missing"))
            val original = web.get().webViewClient
            web.get().webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host == "fixture.example") return WebResourceResponse("text/html", "UTF-8",
                        fixtureHtml.byteInputStream())
                    return original.shouldInterceptRequest(view, request)
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = original.onPageStarted(view, url, favicon)
                override fun onPageFinished(view: WebView, url: String) = original.onPageFinished(view, url)
            }
            web.get().loadUrl("https://fixture.example/chapter/1")
        }
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.runOnUiThread {
                    web.get().evaluateJavascript("document.body.innerText") {
                        pageText.set(JSONTokener(it).nextValue() as? String ?: it)
                    }
                }
                pageText.get().contains("chào ngươi, thế giới!")
            }
        } catch (error: Throwable) {
            compose.runOnUiThread {
                web.get().evaluateJavascript("JSON.stringify({text:document.body.innerText,bridge:typeof ShelfTranslation,script:!!window.shelfTranslator,origin:location.origin,href:location.href})") {
                    android.util.Log.e("ShelfFixture", it)
                }
            }
            throw AssertionError("Translation did not finish; last text: ${pageText.get()}", error)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { compose.onNodeWithText("Reader").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Reader").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onNodeWithContentDescription("Return to web page").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        // Let the WebView disposal effects and late page callbacks run. The old
        // implementation bounced back as soon as extraction became unavailable.
        repeat(4) {
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Return to web page").assertIsDisplayed()
        }
        val localWeb = AtomicReference<WebView>()
        compose.waitUntil(10_000) { compose.runOnUiThread { localWeb.set(findWebView(compose.activity.window.decorView)) }; localWeb.get() != null }
        compose.waitUntil(10_000) { js(localWeb.get(), "!!window.stvToolsReady && !!document.querySelector('.stv-word')") == "true" }
        assertEquals("你好", js(localWeb.get(), "document.querySelector('.stv-word').dataset.src"))
        assertTrue(js(localWeb.get(), "document.getElementById('stv-content').innerText").contains("This is a novel paragraph"))
        js(localWeb.get(), "document.querySelector('.stv-word').dispatchEvent(new MouseEvent('click',{bubbles:true,clientX:100,clientY:160}));true")
        assertEquals("你好", js(localWeb.get(), "document.getElementById('stv-zh').value"))
        assertTrue(js(localWeb.get(), "document.getElementById('stv-hv').value").isNotBlank())
        assertEquals("chào ngươi", js(localWeb.get(), "document.getElementById('stv-smooth').value"))
        screenshot("stv-word.png")
        js(localWeb.get(), "document.getElementById('stv-word-scope').value='story';document.getElementById('stv-word-scope').dispatchEvent(new Event('change'));document.getElementById('stv-custom').value='Chào bạn đặc biệt';document.querySelector('[data-tick=stv-custom]').click();true")
        assertTrue(NameStore.get(compose.activity).story(NameStore.storyKey("https://fixture.example/chapter/1")).contains("你好=Chào bạn đặc biệt"))
        assertFalse(NameStore.get(compose.activity).global().contains("Chào bạn đặc biệt"))
        assertTrue(js(localWeb.get(), "document.getElementById('stv-content').innerText").contains("Chào bạn đặc biệt"))
        assertTrue(js(localWeb.get(), "LocalReader.saveName('global','你好','Xin chào global')").contains("true"))
        assertTrue(js(localWeb.get(), "document.getElementById('stv-content').innerText").contains("Chào bạn đặc biệt"))
        val disk = JSONObject(java.io.File(compose.activity.filesDir, "translation-names.json").readText())
        assertTrue(disk.getJSONObject("stories").getString(NameStore.storyKey("https://fixture.example/chapter/1")).contains("Chào bạn đặc biệt"))
        js(localWeb.get(), "document.getElementById('stv-listen').click();true")
        assertEquals("false", js(localWeb.get(), "document.getElementById('stv-speech').classList.contains('stv-hide')"))
        assertTrue(js(localWeb.get(), "document.getElementById('stv-sentences').children.length").toInt() > 1)
        js(localWeb.get(), "document.getElementById('stv-jump').value='30';document.getElementById('stv-go').click();true")
        compose.waitUntil(5_000) { js(localWeb.get(), "window.scrollY > 0") == "true" }
        screenshot("stv-speech.png")
        js(localWeb.get(), "document.getElementById('stv-names').click();true")
        screenshot("stv-names.png")
        js(localWeb.get(), "document.getElementById('stv-bg').value='#252525';document.getElementById('stv-bg').dispatchEvent(new Event('change'));true")
        assertEquals("#252525", NameStore.get(compose.activity).appearance().getString("background"))
        compose.onNodeWithContentDescription("Return to web page").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Reader").assertExists()
        compose.onNodeWithText("Dictionary").performClick()
        compose.onNodeWithText("Name", substring = false).assertExists()
        compose.onNodeWithText("Internet").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Reader").assertExists()
        compose.onNodeWithText("Books").performClick()
        compose.waitForIdle()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }
    private fun js(web: WebView, script: String): String {
        val result = AtomicReference("")
        val latch = CountDownLatch(1)
        compose.runOnUiThread { web.evaluateJavascript(script) {
            result.set(runCatching { JSONTokener(it).nextValue().toString() }.getOrDefault(it)); latch.countDown()
        } }
        assertTrue("JavaScript callback timed out", latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }
    private fun screenshot(name: String) {
        // Allow the Chromium compositor to publish the DOM changes before capture.
        Thread.sleep(250)
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = automation.takeScreenshot()
        val file = java.io.File(compose.activity.getExternalFilesDir(null), name)
        file.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name")).use { it.readBytes() }
    }
}
