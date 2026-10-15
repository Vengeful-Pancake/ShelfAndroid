package com.shelf.reader.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelf.reader.R
import com.shelf.reader.data.WordTransformer
import com.shelf.reader.ui.theme.ShelfColors
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val annotations by viewModel.annotations.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingQuote by remember { mutableStateOf<String?>(null) }
    var pendingSwipeDirection by remember { mutableStateOf<Int?>(null) }
    val exportNotes = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> if (uri != null) viewModel.exportAnnotations(uri) }

    val view = LocalView.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    // The page pads itself clear of the bars, so text is never hidden behind them.
    val topPadDp = (insets.calculateTopPadding() + 60.dp).value.toInt()
    val bottomPadDp = (insets.calculateBottomPadding() + 80.dp).value.toInt()

    LaunchedEffect(bookId) { viewModel.load(bookId) }

    LaunchedEffect(event) {
        when (val e = event) {
            is ReaderEvent.Toast -> {
                snackbars.showSnackbar(e.message)
                viewModel.consumeEvent()
            }
            is ReaderEvent.TtsHighlight -> {
                if (e.text != null) {
                    webView?.evaluateJavascript("window.shelfHighlight('${e.text.escapeJs()}')", null)
                } else {
                    val pairs = highlightPairs(annotations, state.loadedSections, viewModel::sectionStart)
                    webView?.evaluateJavascript("window.shelfHighlights($pairs)", null)
                }
                viewModel.consumeEvent()
            }
            null -> Unit
        }
    }

    // Reading with the screen going dark every thirty seconds is no use to anyone.
    DisposableEffect(state.settings.keepScreenOn) {
        view.keepScreenOn = state.settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Hide the system bars along with our own, so the page gets the whole screen.
    val window = (view.context as? android.app.Activity)?.window
    DisposableEffect(state.barsVisible, state.settings.fullscreen, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (state.settings.fullscreen && !state.barsVisible) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.saveProgressNow()
                    viewModel.setReadingActive(false)
                    webView?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setReadingActive(true)
                    webView?.onResume()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.setReadingActive(true)
        }
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.setReadingActive(false)
        }
    }

    BackHandler(enabled = sheet != null) { sheet = null }

    val pageColor = Color(android.graphics.Color.parseColor(state.settings.background))
    val textColor = Color(android.graphics.Color.parseColor(state.settings.foreground))
    val transformer = remember(state.settings.wordReplacements, state.settings.wordReplacementsUri) {
        WordTransformer(view.context, state.settings.wordReplacements, state.settings.wordReplacementsUri)
    }

    // Saved highlights are re-marked when they change. Section changes during
    // continuous scrolling do not re-run this, so the page never jumps.
    LaunchedEffect(annotations, state.revision, state.loading) {
        if (!state.loading && state.html.isNotEmpty()) {
            val pairs = highlightPairs(annotations, state.loadedSections, viewModel::sectionStart)
            if (pairs.length() > 0) {
                webView?.evaluateJavascript("window.shelfHighlights($pairs)", null)
            }
        }
    }

    // Continuous scrolling: sections the view model fetched go under the page.
    val currentTransformer by rememberUpdatedState(transformer)
    val latestAnnotations by rememberUpdatedState(annotations)
    LaunchedEffect(webView, bookId) {
        val web = webView ?: return@LaunchedEffect
        viewModel.appends.collect { section ->
            if (web.getTag(R.id.shelf_chapter_revision) != (bookId to section.revision)) return@collect
            val html = ReaderHtml.section(
                section.index,
                section.next,
                section.label,
                section.html,
                currentTransformer::transform,
            )
            web.evaluateJavascript("window.shelfAppend(${section.index}, ${JSONObject.quote(html)})") {
                val pairs = highlightPairs(latestAnnotations, listOf(section.index), viewModel::sectionStart)
                if (pairs.length() > 0) {
                    web.evaluateJavascript("window.shelfAddHighlights($pairs)", null)
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(pageColor)
    ) {
        // ------------------------------------------------------------ the page

        val currentState by rememberUpdatedState(state)
        val currentAnnotations by rememberUpdatedState(annotations)

        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                // Do not leave the previous book visible behind the loading/error UI.
                .alpha(if (state.html.isEmpty()) 0f else 1f),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = false
                        loadsImagesAutomatically = true
                        defaultTextEncodingName = "utf-8"
                        // Zoom is driven by the text size control, not pinch.
                        setSupportZoom(false)
                        builtInZoomControls = false
                    }

                    addJavascriptInterface(
                        ReaderBridge(
                            scrolled = viewModel::onScroll,
                            directionChanged = viewModel::onScrollDirection,
                            tapped = { zone ->
                                post {
                                    when {
                                        zone == 0 -> viewModel.toggleBars()
                                        !currentState.settings.tapToTurn -> viewModel.toggleBars()
                                        zone > 0 -> pageForward(this, viewModel)
                                        else -> pageBack(this, viewModel)
                                    }
                                }
                            },
                            swiped = { direction ->
                                post {
                                    val canChangeChapter = if (direction > 0) {
                                        currentState.canGoForward
                                    } else {
                                        currentState.canGoBack
                                    }
                                    if (canChangeChapter) pendingSwipeDirection = direction
                                }
                            },
                            linkFollowed = { index, fragment ->
                                post { viewModel.openChapter(index, 0f, fragment) }
                            },
                            sectionChanged = viewModel::onSection,
                            needNext = viewModel::requestSection,
                            ready = {
                                post {
                                    val target = currentState
                                    if (target.restoreFragment.isNotBlank()) {
                                        evaluateJavascript(
                                            "window.shelfScrollToId('${target.restoreFragment.escapeJs()}')",
                                            null,
                                        )
                                    } else if (target.restoreOffset > 0f) {
                                        evaluateJavascript(
                                            "window.shelfScrollTo(${target.restoreOffset})",
                                            null,
                                        )
                                    }
                                    val pairs = highlightPairs(
                                        currentAnnotations,
                                        listOf(target.pageIndex),
                                        viewModel::sectionStart,
                                    )
                                    val focus = target.restoreHighlight
                                    if (focus.isNotBlank()) pairs.put(JSONArray().put(target.pageIndex).put(focus))
                                    if (pairs.length() > 0) {
                                        val focusJs = if (focus.isBlank()) "null" else JSONObject.quote(focus)
                                        evaluateJavascript(
                                            "window.shelfHighlights($pairs, $focusJs)",
                                            null,
                                        )
                                    }
                                }
                            },
                        ),
                        "Shelf",
                    )

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            // Nothing leaves the device: anything that is not a file
                            // from inside this book is answered with an empty response.
                            val requested = ReaderHtml.resourceRequest(request.url) ?: return blank()
                            val resource = viewModel.resourceFor(
                                requested.bookId,
                                requested.version,
                                requested.path,
                            )
                                ?: return blank()
                            val (bytes, mime) = resource
                            return WebResourceResponse(
                                mime.substringBefore(';'),
                                null,
                                200,
                                "OK",
                                mapOf(
                                    // The URL contains both the book id and file stamp,
                                    // so cached bytes cannot leak into another/updated book.
                                    "Cache-Control" to "private, max-age=86400",
                                    "X-Content-Type-Options" to "nosniff",
                                ),
                                ByteArrayInputStream(bytes),
                            )
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            // External links open in the browser; internal ones already
                            // came through the JavaScript bridge.
                            val scheme = request.url.scheme
                            if (scheme == "http" || scheme == "https") {
                                if (request.url.host == "shelf.localhost") return true
                                runCatching {
                                    view.context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            request.url,
                                        )
                                    )
                                }
                                return true
                            }
                            return true
                        }
                    }
                    webView = this
                }
            },
            update = { web ->
                // The revision counter makes a repeat of the same chapter still reload.
                val chapterKey = bookId to state.revision
                if (web.getTag(R.id.shelf_chapter_revision) != chapterKey && state.html.isNotEmpty()) {
                    web.setTag(R.id.shelf_chapter_revision, chapterKey)
                    web.setTag(R.id.shelf_settings_key, state.settings)
                    web.loadDataWithBaseURL(
                        ReaderHtml.ORIGIN + "/",
                        ReaderHtml.page(
                            sectionHtml = ReaderHtml.section(
                                index = state.pageIndex,
                                next = state.pageNext,
                                chapterLabel = state.pageLabel,
                                bodyHtml = state.html,
                                transform = transformer::transform,
                            ),
                            settings = state.settings,
                            topInsetPx = topPadDp,
                            bottomInsetPx = bottomPadDp,
                        ),
                        "text/html",
                        "utf-8",
                        null,
                    )
                } else if (web.getTag(R.id.shelf_settings_key) != state.settings) {
                    // Typography changed. Restyling in place keeps the reader's place
                    // on the page, which a reload would throw away.
                    web.setTag(R.id.shelf_settings_key, state.settings)
                    web.evaluateJavascript(
                        ReaderHtml.applySettingsScript(state.settings, topPadDp, bottomPadDp),
                        null,
                    )
                }
            },
            onRelease = { it.destroy() },
        )

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = textColor.copy(alpha = 0.6f))
            }
        }

        state.error?.let { message ->
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    message,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        // ---------------------------------------------------------------- bars

        AnimatedVisibility(
            visible = state.barsVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = state.bookTitle,
                subtitle = if (state.chapterCount > 0)
                    "${state.chapterTitle} · ${state.chapterIndex + 1} of ${state.chapterCount}"
                else "",
                speaking = state.speaking,
                onBack = {
                    viewModel.saveProgressNow()
                    onBack()
                },
                onBookmark = viewModel::addBookmark,
                onHighlight = {
                    webView?.evaluateJavascript("window.shelfSelection()") { result ->
                        val quote = runCatching { JSONTokener(result).nextValue() as? String }
                            .getOrNull().orEmpty().trim()
                        if (quote.isEmpty()) viewModel.addAnnotation("")
                        else pendingQuote = quote
                    }
                },
                onSpeak = viewModel::toggleSpeaking,
            )
        }

        AnimatedVisibility(
            visible = state.barsVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                progress = state.scrollFraction,
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                speaking = state.speaking,
                ttsProgress = state.ttsProgress,
                ttsSpeed = state.settings.ttsSpeed,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onContents = { sheet = ReaderSheet.CONTENTS },
                onBookmarks = { sheet = ReaderSheet.BOOKMARKS },
                onSearch = { sheet = ReaderSheet.SEARCH },
                onNotes = { sheet = ReaderSheet.NOTES },
                onSettings = { sheet = ReaderSheet.SETTINGS },
                onProgressChange = { fraction ->
                    webView?.evaluateJavascript("window.shelfScrollTo($fraction)", null)
                    viewModel.onScroll(fraction)
                },
                onProgressChangeFinished = viewModel::saveProgressNow,
                onTtsSeek = viewModel::seekTts,
                onTtsSpeedChange = viewModel::setTtsSpeed,
                onTtsVoices = { sheet = ReaderSheet.VOICES }
            )
        }

        SnackbarHost(
            snackbars,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 84.dp),
        )
    }

    when (sheet) {
        ReaderSheet.CONTENTS -> ContentsSheet(
            toc = state.toc,
            currentChapter = state.chapterIndex,
            onDismiss = { sheet = null },
            onSelect = { entry ->
                sheet = null
                viewModel.openChapter(entry.spineIndex, 0f, entry.fragment)
            },
        )
        ReaderSheet.BOOKMARKS -> BookmarksSheet(
            bookmarks = bookmarks,
            onDismiss = { sheet = null },
            onOpen = { mark ->
                sheet = null
                viewModel.openBookmark(mark)
            },
            onDelete = viewModel::deleteBookmark,
        )
        ReaderSheet.SEARCH -> SearchSheet(
            query = state.searchQuery,
            results = state.searchResults,
            searching = state.searchingBook,
            onQueryChange = viewModel::searchBook,
            onDismiss = { sheet = null },
            onOpen = {
                sheet = null
                viewModel.openSearchResult(it)
            },
        )
        ReaderSheet.NOTES -> AnnotationsSheet(
            annotations = annotations,
            onDismiss = { sheet = null },
            onOpen = {
                sheet = null
                viewModel.openAnnotation(it)
            },
            onDelete = viewModel::deleteAnnotation,
            onExport = {
                exportNotes.launch("${state.bookTitle.ifBlank { "book" }} notes.md")
            },
        )
        ReaderSheet.SETTINGS -> ReaderSettingsSheet(
            settings = state.settings,
            onDismiss = { sheet = null },
            onChange = viewModel::updateSettings,
        )
        ReaderSheet.VOICES -> VoicesSheet(
            voices = state.availableVoices,
            selected = state.settings.ttsVoice,
            onDismiss = { sheet = null },
            onSelect = {
                viewModel.setTtsVoice(it)
                sheet = null
            }
        )
        null -> Unit
    }

    pendingQuote?.let { quote ->
        AddAnnotationDialog(
            quote = quote,
            onDismiss = { pendingQuote = null },
            onSave = { note ->
                pendingQuote = null
                viewModel.addAnnotation(quote, note)
            },
        )
    }

    pendingSwipeDirection?.let { direction ->
        val movingForward = direction > 0
        AlertDialog(
            onDismissRequest = { pendingSwipeDirection = null },
            containerColor = ShelfColors.Panel,
            title = {
                Text(
                    if (movingForward) "Go to the next section?" else "Go to the previous section?",
                    color = ShelfColors.Text,
                )
            },
            text = {
                Text(
                    "Confirm this swipe to avoid changing sections by accident.",
                    color = ShelfColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwipeDirection = null
                    if (movingForward) viewModel.next() else viewModel.previous()
                }) {
                    Text(if (movingForward) "Next section" else "Previous section", color = ShelfColors.Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwipeDirection = null }) {
                    Text("Stay here", color = ShelfColors.Muted)
                }
            },
        )
    }
}

enum class ReaderSheet { CONTENTS, BOOKMARKS, SEARCH, NOTES, SETTINGS, VOICES }

// ------------------------------------------------------------------ page turn

private fun pageForward(web: WebView, viewModel: ReaderViewModel) {
    web.evaluateJavascript("window.shelfAtEnd()") { result ->
        if (result == "true") viewModel.next() else web.evaluateJavascript("window.shelfPage(1)", null)
    }
}

private fun pageBack(web: WebView, viewModel: ReaderViewModel) {
    web.evaluateJavascript("window.shelfAtStart()") { result ->
        if (result == "true") viewModel.previous() else web.evaluateJavascript("window.shelfPage(-1)", null)
    }
}

private fun String.escapeJs(): String =
    replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

private fun blank(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

/** The page talks back through this. Method names are referenced from JavaScript. */
class ReaderBridge(
    private val scrolled: (Float) -> Unit,
    private val directionChanged: (Boolean) -> Unit,
    private val tapped: (Int) -> Unit,
    private val swiped: (Int) -> Unit,
    private val linkFollowed: (Int, String) -> Unit,
    private val ready: () -> Unit,
    private val sectionChanged: (Int) -> Unit,
    private val needNext: (Int) -> Unit,
) {
    @JavascriptInterface
    fun onScroll(fraction: Double) = scrolled(fraction.toFloat())

    @JavascriptInterface
    fun onScrollDirection(scrollingDown: Boolean) = directionChanged(scrollingDown)

    @JavascriptInterface
    fun onTap(zone: Int) = tapped(zone)

    @JavascriptInterface
    fun onSwipe(direction: Int) = swiped(direction)

    @JavascriptInterface
    fun onInternalLink(index: Int, fragment: String) = linkFollowed(index, fragment)

    @JavascriptInterface
    fun onReady() = ready()

    @JavascriptInterface
    fun onSection(index: Int) = sectionChanged(index)

    @JavascriptInterface
    fun onNeedNext(index: Int) = needNext(index)
}

/** Saved highlights for the sections on the page, as `[[section, quote], …]` for the script. */
private fun highlightPairs(
    annotations: List<com.shelf.reader.data.AnnotationEntity>,
    sections: List<Int>,
    sectionStart: (Int) -> Int,
): JSONArray {
    val out = JSONArray()
    annotations.forEach { mark ->
        val section = sectionStart(mark.chapter)
        if (section in sections) out.put(JSONArray().put(section).put(mark.quote))
    }
    return out
}

// ---------------------------------------------------------------------- bars

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String,
    speaking: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onHighlight: () -> Unit,
    onSpeak: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ShelfColors.Panel.copy(alpha = 0.96f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back to the library",
                tint = ShelfColors.Text,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = ShelfColors.Text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ShelfColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onSpeak) {
            Icon(
                if (speaking) Icons.Filled.Stop else Icons.Outlined.RecordVoiceOver,
                contentDescription = if (speaking) "Stop reading aloud" else "Read aloud",
                tint = if (speaking) ShelfColors.Rose else ShelfColors.Text,
            )
        }
        IconButton(onClick = onBookmark) {
            Icon(
                Icons.Outlined.BookmarkAdd,
                contentDescription = "Save my place here",
                tint = ShelfColors.Text,
            )
        }
        IconButton(onClick = onHighlight) {
            Icon(
                Icons.Outlined.BorderColor,
                contentDescription = "Highlight selected text",
                tint = ShelfColors.Text,
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    progress: Float,
    canGoBack: Boolean,
    canGoForward: Boolean,
    speaking: Boolean,
    ttsProgress: Float,
    ttsSpeed: Float,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onContents: () -> Unit,
    onBookmarks: () -> Unit,
    onSearch: () -> Unit,
    onNotes: () -> Unit,
    onSettings: () -> Unit,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit,
    onTtsSeek: (Float) -> Unit,
    onTtsSpeedChange: (Float) -> Unit,
    onTtsVoices: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ShelfColors.Panel.copy(alpha = 0.96f)),
    ) {
        if (speaking) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onTtsVoices) {
                        Text(
                            "Voice Settings",
                            style = MaterialTheme.typography.labelMedium,
                            color = ShelfColors.Rose
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            TextButton(
                                onClick = { onTtsSpeedChange(speed) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(
                                    "${speed}x",
                                    color = if (ttsSpeed == speed) ShelfColors.Rose else ShelfColors.Text,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
                Slider(
                    value = ttsProgress,
                    onValueChange = onTtsSeek,
                    colors = SliderDefaults.colors(
                        thumbColor = ShelfColors.Rose,
                        activeTrackColor = ShelfColors.Rose,
                        inactiveTrackColor = ShelfColors.Hair
                    )
                )
            }
        }

        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = onProgressChange,
            onValueChangeFinished = onProgressChangeFinished,
            modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = ShelfColors.Rose,
                activeTrackColor = ShelfColors.Rose,
                inactiveTrackColor = ShelfColors.Hair,
            ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = canGoBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.NavigateBefore,
                    contentDescription = "Previous section",
                    tint = if (canGoBack) ShelfColors.Text else ShelfColors.Hair,
                )
            }
            IconButton(onClick = onContents) {
                Icon(Icons.AutoMirrored.Outlined.Toc, contentDescription = "Contents", tint = ShelfColors.Text)
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, contentDescription = "Search this book", tint = ShelfColors.Text)
            }
            IconButton(onClick = onBookmarks) {
                Icon(Icons.Outlined.Bookmarks, contentDescription = "Bookmarks", tint = ShelfColors.Text)
            }
            IconButton(onClick = onNotes) {
                Icon(Icons.Outlined.EditNote, contentDescription = "Highlights and notes", tint = ShelfColors.Text)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.FormatSize, contentDescription = "Reading settings", tint = ShelfColors.Text)
            }
            IconButton(onClick = onNext, enabled = canGoForward) {
                Icon(
                    Icons.AutoMirrored.Outlined.NavigateNext,
                    contentDescription = "Next section",
                    tint = if (canGoForward) ShelfColors.Text else ShelfColors.Hair,
                )
            }
        }
    }
}
