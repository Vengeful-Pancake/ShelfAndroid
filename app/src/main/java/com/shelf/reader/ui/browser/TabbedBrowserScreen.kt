package com.shelf.reader.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.shelf.reader.ShelfApp
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.translation.ChineseTranslator
import com.shelf.reader.translation.NameStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private class TabPage(val web: WebView) {
    var loading by mutableStateOf(true)
    var back by mutableStateOf(false)
    var forward by mutableStateOf(false)
    var article by mutableStateOf<WebArticle?>(null)
    var translated = false
    var epoch = 0
    var busy = AtomicBoolean(false)
    var lastUsed = 0L
    var scrollSave: Runnable? = null
    var error = false
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onBack: () -> Unit, onNavigationSettings: () -> Unit = {}, viewModel: BrowserViewModel = viewModel()) {
    val context = LocalContext.current
    val store = remember { BrowserStore.get(context) }
    val data by store.state.collectAsStateWithLifecycle()
    val settingsStore = remember { ShelfApp.repository(context).settings }
    val settings by settingsStore.flow.collectAsStateWithLifecycle(initialValue = ReaderSettings())
    val latestSettings by rememberUpdatedState(settings)
    val enabled = settings.browserExtensionsEnabled
    val latestEnabled by rememberUpdatedState(enabled)
    val nameRevision by remember { NameStore.get(context).revision }.collectAsStateWithLifecycle()
    val script = remember { context.assets.open("stv-reader.js").bufferedReader().use { it.readText() } }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val pages = remember { mutableStateMapOf<String, TabPage>() }
    var address by rememberSaveable { mutableStateOf(data.active.url) }
    var menu by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var reader by remember { mutableStateOf<WebArticle?>(null) }
    val tab = data.active
    val active = pages[tab.id]

    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun openTab(url: String = BROWSER_HOME) { runCatching { store.update { it.open(url) } }.onFailure { message(it.message ?: "Cannot open tab") } }
    fun save(id: String, page: TabPage) {
        runCatching { page.web.saveState(Bundle().also { viewModel.webStates[id] = it }) }
        store.tab(id) { it.copy(scrollX = page.web.scrollX, scrollY = page.web.scrollY) }
    }
    fun release(id: String, page: TabPage, preserve: Boolean) {
        if (preserve) save(id, page) else viewModel.webStates.remove(id)
        page.epoch++
        page.scrollSave?.let { page.web.removeCallbacks(it) }
        (page.web.parent as? ViewGroup)?.removeView(page.web)
        page.web.stopLoading(); page.web.destroy()
    }
    fun closeTab(id: String) {
        store.update { it.close(id) }
        pages.remove(id)?.let { release(id, it, false) }
        viewModel.webStates.remove(id)
    }
    fun extract(id: String, page: TabPage) {
        val epoch = page.epoch
        val url = page.web.url ?: return
        page.web.evaluateJavascript(ARTICLE_SCRIPT) { raw ->
            if (pages[id] === page && page.epoch == epoch && page.web.url == url) {
                page.article = decodeArticle(raw, url)?.let { if (page.translated) it.copy(language = "vi") else it }
            }
        }
    }
    fun extensions(id: String, page: TabPage) {
        page.epoch++; page.busy = AtomicBoolean(false); page.translated = false
        page.web.evaluateJavascript("if(window.shelfTranslator)window.shelfTranslator.dispose();" + if (latestEnabled) script else "") { extract(id, page) }
    }
    fun makePage(value: BrowserTab): TabPage {
        val web = WebView(context)
        val page = TabPage(web)
        pages[value.id] = page
        var restoreScroll: Pair<Int, Int>? = value.scrollX to value.scrollY
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; safeBrowsingEnabled = true
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.addWebMessageListener(web, "ShelfTranslation", WebNavigationPolicy.translationOriginRules) { source, message, origin, mainFrame, reply ->
                val epoch = page.epoch
                val busy = page.busy
                val pageUrl = source.url.orEmpty()
                val configuration = latestSettings.copy(translationEnabled = true)
                val raw = if (latestEnabled && WebNavigationPolicy.acceptsTranslationOrigin(origin.toString(), source.url, mainFrame) && message.type == androidx.webkit.WebMessageCompat.TYPE_STRING) message.data else null
                if (raw != null && raw.length <= 200_000 && busy.compareAndSet(false, true)) scope.launch(Dispatchers.Main.immediate) {
                    try {
                        val response = withContext(Dispatchers.IO) { runCatching {
                            val request = JSONObject(raw); val texts = request.getJSONArray("texts")
                            require(texts.length() in 1..32 && request.getString("id").length in 1..80)
                            require((0 until texts.length()).sumOf { texts.getString(it).length } <= 32_000)
                            val translator = ChineseTranslator(context, configuration, NameStore.storyKey(pageUrl))
                            val output = JSONArray()
                            for (i in 0 until texts.length()) { val text = texts.getString(i); require(text.length <= 32_000); output.put(translator.plain(text)) }
                            JSONObject().put("id", request.getString("id")).put("texts", output)
                        }.getOrElse { JSONObject().put("id", runCatching { JSONObject(raw).optString("id") }.getOrDefault("")).put("error", "Translation failed") } }
                        if (pages[value.id] === page && page.epoch == epoch && latestEnabled && WebNavigationPolicy.acceptsTranslationOrigin(origin.toString(), source.url, true)) {
                            reply.postMessage(response.toString()); if (!response.has("error")) page.translated = true
                            extract(value.id, page)
                        }
                    } finally { busy.set(false) }
                }
            } }.onFailure { message("Web translation is unavailable in this WebView. Reader can still translate.") }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) { store.tab(value.id) { it.copy(title = title.orEmpty().take(300).ifBlank { view.url ?: "New tab" }) } }
            override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, result: Message): Boolean {
                if (!userGesture || store.state.value.tabs.size >= 20) return false
                val popup = WebView(context)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                popup.settings.allowFileAccess = false; popup.settings.allowContentAccess = false
                val opened = AtomicBoolean(false)
                fun capture(url: String) { if (WebNavigationPolicy.isSecureWebUrl(url) && opened.compareAndSet(false, true)) { openTab(url); handler.post { popup.destroy() } } }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { capture(request.url.toString()); return true }
                    override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) { capture(url) }
                }
                (result.obj as WebView.WebViewTransport).webView = popup; result.sendToTarget()
                handler.postDelayed({ if (opened.compareAndSet(false, true)) popup.destroy() }, 5000)
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) {
                page.epoch++; page.busy = AtomicBoolean(false); page.loading = true; page.error = false; page.article = null; page.translated = false
                if (url != value.url) restoreScroll = null
                if (WebNavigationPolicy.isSecureWebUrl(url) && url.length <= 8192) store.tab(value.id) { it.copy(url = url, scrollX = if (it.url == url) it.scrollX else 0, scrollY = if (it.url == url) it.scrollY else 0) }
                page.back = view.canGoBack(); page.forward = view.canGoForward()
            }
            override fun onPageFinished(view: WebView, url: String) {
                page.loading = false; page.back = view.canGoBack(); page.forward = view.canGoForward()
                if (WebNavigationPolicy.isSecureWebUrl(url) && url.length <= 8192) {
                    store.tab(value.id) { it.copy(url = url, title = view.title.orEmpty().take(300)) }
                    if (!page.error) store.update { it.visit(url, view.title.orEmpty()) }
                    extensions(value.id, page)
                }
                restoreScroll?.let { (x, y) -> view.post { view.scrollTo(x, y) } }; restoreScroll = null
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) { page.error = true; page.loading = false } }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null && WebNavigationPolicy.isSecureWebUrl(url) && url.length <= 8192) {
                    store.tab(value.id) { it.copy(url = url) }
                    if (!page.error) store.update { it.visit(url, view.title.orEmpty()) }
                    view.postDelayed({ if (pages[value.id] === page) extract(value.id, page) }, 200)
                }
                page.back = view.canGoBack(); page.forward = view.canGoForward()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (WebNavigationPolicy.isSecureWebUrl(url)) return false
                if (request.isForMainFrame && request.url.scheme.equals("http", true)) { val secure = normalizeAddress(url); if (WebNavigationPolicy.isSecureWebUrl(secure)) view.loadUrl(secure); return true }
                if (WebNavigationPolicy.mayOpenExternal(url, request.isForMainFrame, request.hasGesture())) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
        web.setOnLongClickListener {
            val hit = web.hitTestResult
            val url = hit.extra
            if (hit.type in listOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && url != null && WebNavigationPolicy.isSecureWebUrl(url)) { link = url; true } else false
        }
        web.setOnScrollChangeListener { _, _, _, _, _ ->
            page.scrollSave?.let { web.removeCallbacks(it) }
            page.scrollSave = Runnable { store.tab(value.id) { it.copy(scrollX = web.scrollX, scrollY = web.scrollY) } }.also { web.postDelayed(it, 400) }
        }
        val saved = viewModel.webStates[value.id]
        if (saved == null || web.restoreState(saved) == null) web.loadUrl(value.url)
        return page
    }

    LaunchedEffect(tab.id, tab.url) { address = tab.url }
    LaunchedEffect(enabled, settings.globalNames, settings.globalTerms, nameRevision) { withContext(Dispatchers.Main.immediate) { pages.toMap().forEach { (id, page) -> extensions(id, page) } } }
    DisposableEffect(Unit) { onDispose { pages.toMap().forEach { (id, page) -> release(id, page, true) }; pages.clear() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) pages.toMap().forEach { (id, page) -> save(id, page); page.web.onPause() }
            if (event == Lifecycle.Event.ON_RESUME) pages[store.state.value.activeId]?.web?.onResume()
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { when { panel != null -> panel = null; reader != null -> reader = null; active?.web?.canGoBack() == true -> active.web.goBack(); else -> onBack() } }

    if (reader != null) { com.shelf.reader.ui.reader.InteractiveWebReader(reader!!, onBack = { reader = null }); return }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column {
            Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library") }
                TextField(address, { address = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("Address or search") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); active?.web?.loadUrl(normalizeAddress(address)) }))
                IconButton(onClick = { keyboard?.hide(); active?.web?.loadUrl(normalizeAddress(address)) }) { Icon(Icons.Outlined.Search, "Go") }
                TextButton(onClick = { panel = "Tabs" }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(40.dp)) { Text("${data.tabs.size}", modifier = Modifier.padding(4.dp)); Icon(Icons.Outlined.Tab, "Browser tabs", Modifier.size(18.dp)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Browser menu") }
                    DropdownMenu(menu, { menu = false }) {
                        listOf("New tab", "History", "Favorites", "Extensions", "Navigation bar").forEach { action -> DropdownMenuItem(text = { Text(action) }, onClick = {
                            menu = false; when (action) { "New tab" -> openTab(); "Navigation bar" -> onNavigationSettings(); else -> panel = action }
                        }) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { active?.web?.goBack() }, enabled = active?.back == true) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous page") }
                IconButton(onClick = { active?.web?.goForward() }, enabled = active?.forward == true) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Next page") }
                IconButton(onClick = { if (active?.loading == true) active.web.stopLoading() else active?.web?.reload() }) { Icon(if (active?.loading == true) Icons.Outlined.Close else Icons.Outlined.Refresh, if (active?.loading == true) "Stop loading" else "Reload") }
                IconButton(onClick = { active?.web?.loadUrl(BROWSER_HOME) }) { Icon(Icons.Outlined.Home, "Home") }
                TextButton(onClick = { active?.web?.onPause(); reader = active?.article }, enabled = active?.article != null, contentPadding = PaddingValues(4.dp)) { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(18.dp)); Text("Reader") }
                IconButton(onClick = { panel = "Extensions" }) { Icon(if (enabled) Icons.Outlined.Extension else Icons.Outlined.ExtensionOff, "Extensions", tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { runCatching { store.favorite(tab.url, tab.title) }.onFailure { message(it.message.orEmpty()) } }) { Icon(if (data.favorites.any { it.url == tab.url }) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Toggle favorite") }
            }
            if (active?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
    }) { padding ->
        AndroidView(modifier = Modifier.padding(padding).fillMaxSize(), factory = { FrameLayout(it) }, update = { host ->
            val page = pages[tab.id] ?: makePage(tab)
            if (page.web.parent !== host) {
                pages.filterKeys { it != tab.id }.values.forEach { it.web.onPause() }
                host.removeAllViews(); (page.web.parent as? ViewGroup)?.removeView(page.web)
                host.addView(page.web, FrameLayout.LayoutParams(-1, -1)); page.web.onResume(); page.lastUsed = System.currentTimeMillis()
                while (pages.size > 3) pages.filterKeys { it != tab.id }.minByOrNull { it.value.lastUsed }?.let { (id, old) -> pages.remove(id); release(id, old, true) }
            }
        }, onRelease = { host -> host.removeAllViews() })
    }
    if (panel != null) BrowserPanel(panel!!, data, settings, onDismiss = { panel = null },
        onSelectTab = { store.select(it); panel = null }, onCloseTab = ::closeTab, onNewTab = { openTab(); panel = null },
        onOpen = { url, new -> if (new) openTab(url) else active?.web?.loadUrl(url); panel = null },
        onFavoriteRemove = { url -> store.update { it.copy(favorites = it.favorites.filterNot { entry -> entry.url == url }) } },
        onHistoryRemove = { id -> store.update { it.copy(history = it.history.filterNot { entry -> entry.id == id }) } },
        onHistoryClear = { store.update { it.copy(history = emptyList()) } },
        onExtension = { value -> scope.launch { settingsStore.update { it.copy(browserExtensionsEnabled = value) } } })
    if (link != null) AlertDialog(onDismissRequest = { link = null }, title = { Text("Link") }, text = { Text(link!!) },
        confirmButton = { TextButton(onClick = { openTab(link!!); link = null }) { Text("Open in new tab") } },
        dismissButton = { TextButton(onClick = { link = null }) { Text("Cancel") } })
}
