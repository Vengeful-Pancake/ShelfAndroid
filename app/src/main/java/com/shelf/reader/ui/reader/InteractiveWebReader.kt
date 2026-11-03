package com.shelf.reader.ui.reader

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shelf.reader.ShelfApp
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.translation.ChineseTranslator
import com.shelf.reader.translation.NameStore
import com.shelf.reader.ui.browser.WebArticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractiveWebReader(article: WebArticle, onBack: () -> Unit) {
    val context = LocalContext.current
    val story = remember(article.url) { NameStore.storyKey(article.url) }
    val readerSettings by remember { ShelfApp.repository(context).settings.flow }.collectAsStateWithLifecycle(initialValue = ReaderSettings())
    val controller = remember(story) { LocalReaderController(context, story, article.title) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val html by produceState<String?>(null, article.url, article.sourceText) {
        value = withContext(Dispatchers.IO) {
            val translator = ChineseTranslator(context, readerSettings, story)
            val body = article.sourceText.lineSequence().filter { it.isNotBlank() }.joinToString("") { "<p>${ChineseTranslator.escape(it)}</p>" }
            val content = translator.interactiveHtml("<h1>${ChineseTranslator.escape(article.title)}</h1>$body")
            "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'></head><body class='stv-local'><main id='stv-content'>$content</main></body></html>"
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(article.title, maxLines = 1) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Return to web page") }
    }) }) { padding ->
        if (html == null) CircularProgressIndicator(Modifier.padding(padding)) else AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { ctx -> WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webChromeClient = WebChromeClient()
                controller.attach(this)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    override fun onPageFinished(view: WebView, url: String) { controller.install() }
                }
                loadDataWithBaseURL("https://shelf-reader.localhost/", html!!, "text/html", "UTF-8", null)
            } }, onRelease = { controller.close(); it.removeJavascriptInterface("LocalReader"); it.destroy() },
        )
    }
}
