package com.shelf.reader.ui.browser

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelf.reader.ui.theme.ShelfColors
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONTokener

private const val HOME_PAGE = "https://duckduckgo.com/"

data class WebArticle(
    val url: String,
    val title: String,
    val author: String,
    val language: String,
    val text: String,
)

data class WebSpeechState(
    val speaking: Boolean = false,
    val progress: Float = 0f,
    val speed: Float = 1f,
    val message: String? = null,
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val _speech = MutableStateFlow(WebSpeechState())
    val speech = _speech.asStateFlow()

    private var article: WebArticle? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var wantsToSpeak = false
    private var pieces: List<String> = emptyList()

    fun setArticle(value: WebArticle) {
        if (article?.url != value.url || article?.text != value.text) stopSpeaking()
        article = value
    }

    fun toggleSpeaking() {
        if (_speech.value.speaking) stopSpeaking() else startSpeaking()
    }

    fun setSpeed(value: Float) {
        _speech.value = _speech.value.copy(speed = value)
        if (_speech.value.speaking) {
            val index = (_speech.value.progress * pieces.size).toInt().coerceIn(0, pieces.lastIndex)
            speak(index)
        }
    }

    fun seek(progress: Float) {
        if (pieces.isEmpty()) return
        val index = (progress * pieces.size).toInt().coerceIn(0, pieces.lastIndex)
        speak(index)
    }

    fun consumeMessage() { _speech.value = _speech.value.copy(message = null) }

    fun stopSpeaking() {
        wantsToSpeak = false
        tts?.stop()
        _speech.value = _speech.value.copy(speaking = false)
    }

    private fun startSpeaking() {
        if (article?.text.isNullOrBlank()) {
            _speech.value = _speech.value.copy(message = "Reader text is not available")
            return
        }
        wantsToSpeak = true
        if (tts == null) {
            tts = TextToSpeech(getApplication()) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready && wantsToSpeak) speak(0)
                else _speech.value = _speech.value.copy(message = "No speech engine is available")
            }.also { engine ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val index = utteranceId?.substringAfter("web-")?.toIntOrNull() ?: return
                        _speech.value = _speech.value.copy(
                            speaking = true,
                            progress = index.toFloat() / pieces.size.coerceAtLeast(1),
                        )
                    }

                    override fun onDone(utteranceId: String?) {
                        val index = utteranceId?.substringAfter("web-")?.toIntOrNull() ?: return
                        if (index == pieces.lastIndex) {
                            wantsToSpeak = false
                            _speech.value = _speech.value.copy(speaking = false, progress = 1f)
                        }
                    }

                    @Deprecated("Kept for older speech engines")
                    override fun onError(utteranceId: String?) {
                        wantsToSpeak = false
                        _speech.value = _speech.value.copy(speaking = false, message = "Read aloud stopped")
                    }
                })
            }
        } else if (ready) {
            speak(0)
        }
    }

    private fun speak(fromIndex: Int) {
        val value = article ?: return
        val engine = tts ?: return
        if (fromIndex == 0 || pieces.isEmpty()) pieces = chunk(value.text)
        if (pieces.isEmpty()) return
        wantsToSpeak = true

        val locale = value.language.takeIf { it.isNotBlank() }
            ?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }
            ?: Locale.getDefault()
        val languageResult = engine.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) engine.setLanguage(Locale.getDefault())
        engine.setSpeechRate(_speech.value.speed)
        engine.stop()
        for (index in fromIndex until pieces.size) {
            engine.speak(
                pieces[index],
                if (index == fromIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "web-$index",
            )
        }
        _speech.value = _speech.value.copy(
            speaking = true,
            progress = fromIndex.toFloat() / pieces.size,
        )
    }

    private fun chunk(text: String, limit: Int = 2_000): List<String> {
        val output = ArrayList<String>()
        val buffer = StringBuilder()
        text.split(Regex("(?<=[.!?。！？])\\s+")).forEach { sentence ->
            val clean = sentence.trim()
            if (clean.isEmpty()) return@forEach
            if (buffer.length + clean.length > limit && buffer.isNotEmpty()) {
                output.add(buffer.toString().trim())
                buffer.setLength(0)
            }
            if (clean.length > limit) {
                if (buffer.isNotEmpty()) {
                    output.add(buffer.toString().trim())
                    buffer.setLength(0)
                }
                output.addAll(clean.chunked(limit))
            } else {
                buffer.append(clean).append(' ')
            }
        }
        if (buffer.isNotEmpty()) output.add(buffer.toString().trim())
        return output
    }

    override fun onCleared() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onBack: () -> Unit,
    viewModel: BrowserViewModel = viewModel(),
) {
    val speech by viewModel.speech.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(HOME_PAGE) }
    var currentUrl by remember { mutableStateOf(HOME_PAGE) }
    var pageTitle by remember { mutableStateOf("Web browser") }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var article by remember { mutableStateOf<WebArticle?>(null) }
    var readerMode by remember { mutableStateOf(false) }

    fun navigate() {
        val target = normalizeAddress(address)
        address = target
        keyboard?.hide()
        webView?.loadUrl(target)
    }

    BackHandler {
        when {
            readerMode -> {
                viewModel.stopSpeaking()
                readerMode = false
            }
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onBack()
        }
    }

    LaunchedEffect(speech.message) {
        speech.message?.let {
            snackbars.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopSpeaking() }
    }

    if (readerMode && article != null) {
        ArticleReader(
            article = article!!,
            speech = speech,
            onBack = {
                viewModel.stopSpeaking()
                readerMode = false
            },
            onToggleSpeaking = viewModel::toggleSpeaking,
            onSpeed = viewModel::setSpeed,
            onSeek = viewModel::seek,
            snackbarHost = { SnackbarHost(snackbars) },
        )
        return
    }

    BrowserScaffold(
        address = address,
        onAddressChange = { address = it },
        onGo = ::navigate,
        pageTitle = pageTitle,
        loading = loading,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        readerAvailable = article != null,
        onBack = onBack,
        onWebBack = { webView?.goBack() },
        onWebForward = { webView?.goForward() },
        onReload = { webView?.reload() },
        onHome = { address = HOME_PAGE; webView?.loadUrl(HOME_PAGE) },
        onReader = {
            article?.let {
                viewModel.setArticle(it)
                readerMode = true
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { modifier ->
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.WHITE)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mediaPlaybackRequiresUserGesture = true
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            pageTitle = title.orEmpty().ifBlank { "Web browser" }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            loading = true
                            article = null
                            currentUrl = url
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            currentUrl = url
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                            view.evaluateJavascript(ARTICLE_SCRIPT) { raw ->
                                article = decodeArticle(raw, url)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val scheme = request.url.scheme.orEmpty().lowercase()
                            if (scheme == "http" || scheme == "https") return false
                            runCatching {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            }
                            return true
                        }
                    }
                    webView = this
                    loadUrl(currentUrl)
                }
            },
            update = { webView = it },
            onRelease = { web ->
                if (webView === web) webView = null
                web.stopLoading()
                web.loadUrl("about:blank")
                web.clearHistory()
                web.removeAllViews()
                web.destroy()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScaffold(
    address: String,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
    pageTitle: String,
    loading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    readerAvailable: Boolean,
    onBack: () -> Unit,
    onWebBack: () -> Unit,
    onWebForward: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onReader: () -> Unit,
    snackbarHost: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = ShelfColors.Ink,
        snackbarHost = snackbarHost,
        topBar = {
            Column(Modifier.background(ShelfColors.Panel)) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library")
                        }
                    },
                    title = {
                        TextField(
                            value = address,
                            onValueChange = onAddressChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Address or search") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onGo() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = ShelfColors.Raised,
                                unfocusedContainerColor = ShelfColors.Raised,
                                focusedIndicatorColor = ShelfColors.Rose,
                                unfocusedIndicatorColor = ShelfColors.Hair,
                            ),
                        )
                    },
                    actions = {
                        IconButton(onClick = onGo) { Icon(Icons.Outlined.Search, "Go") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ShelfColors.Panel,
                        navigationIconContentColor = ShelfColors.Text,
                        actionIconContentColor = ShelfColors.Text,
                    ),
                )
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row {
                        IconButton(onClick = onWebBack, enabled = canGoBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous page")
                        }
                        IconButton(onClick = onWebForward, enabled = canGoForward) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Next page")
                        }
                        IconButton(onClick = onReload) { Icon(Icons.Outlined.Refresh, "Reload") }
                        IconButton(onClick = onHome) { Icon(Icons.Outlined.Home, "Home") }
                    }
                    Text(
                        pageTitle,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = ShelfColors.Muted,
                    )
                    TextButton(
                        onClick = onReader,
                        enabled = readerAvailable,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = ShelfColors.Rose,
                            disabledContentColor = ShelfColors.Muted.copy(alpha = 0.45f),
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text("Reader")
                    }
                }
                if (loading) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = ShelfColors.Rose,
                    trackColor = ShelfColors.Hair,
                )
            }
        },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleReader(
    article: WebArticle,
    speech: WebSpeechState,
    onBack: () -> Unit,
    onToggleSpeaking: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    snackbarHost: @Composable () -> Unit,
) {
    val page = Color(0xFFECEBE8)
    val ink = Color(0xFF1F1F1F)
    var draggingSpeech by remember { mutableStateOf(false) }
    var pendingSpeechProgress by remember { mutableFloatStateOf(speech.progress) }
    LaunchedEffect(speech.progress, draggingSpeech) {
        if (!draggingSpeech) pendingSpeechProgress = speech.progress
    }
    Scaffold(
        containerColor = page,
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Return to web page")
                    }
                },
                title = {
                    Column {
                        Text(
                            article.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (article.author.isNotBlank()) Text(
                            article.author,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onToggleSpeaking) {
                        Icon(
                            if (speech.speaking) Icons.Filled.Stop else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                        )
                        Text(if (speech.speaking) "Stop" else "AI Read")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ShelfColors.Panel,
                    titleContentColor = ShelfColors.Text,
                    navigationIconContentColor = ShelfColors.Text,
                    actionIconContentColor = ShelfColors.Rose,
                ),
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(ShelfColors.Panel)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Slider(
                    value = pendingSpeechProgress.coerceIn(0f, 1f),
                    onValueChange = {
                        draggingSpeech = true
                        pendingSpeechProgress = it
                    },
                    onValueChangeFinished = {
                        draggingSpeech = false
                        onSeek(pendingSpeechProgress)
                    },
                    enabled = speech.speaking,
                    colors = SliderDefaults.colors(
                        thumbColor = ShelfColors.Rose,
                        activeTrackColor = ShelfColors.Rose,
                        inactiveTrackColor = ShelfColors.Hair,
                    ),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        TextButton(onClick = { onSpeed(speed) }) {
                            Text(
                                "${speed}×",
                                color = if (speech.speed == speed) ShelfColors.Rose else ShelfColors.Text,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        SelectionContainer {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(page)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 24.dp)
            ) {
                Text(
                    article.title,
                    color = ink,
                    fontSize = 30.sp,
                    lineHeight = 37.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                )
                if (article.author.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(article.author, color = ink.copy(alpha = 0.65f), fontSize = 15.sp)
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    article.text,
                    color = ink,
                    fontSize = 19.sp,
                    lineHeight = 32.sp,
                    fontFamily = FontFamily.Serif,
                )
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

private fun normalizeAddress(input: String): String {
    val value = input.trim()
    if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
    if (!value.contains(' ') && value.contains('.')) return "https://$value"
    return "https://duckduckgo.com/?q=${Uri.encode(value)}"
}

private fun decodeArticle(raw: String, url: String): WebArticle? = runCatching {
    val first = JSONTokener(raw).nextValue()
    val json = when (first) {
        is String -> JSONObject(first)
        is JSONObject -> first
        else -> return null
    }
    if (!json.optBoolean("available")) return null
    WebArticle(
        url = url,
        title = json.optString("title").ifBlank { "Reader" },
        author = json.optString("author"),
        language = json.optString("language"),
        text = json.optString("text"),
    ).takeIf { it.text.length >= 600 }
}.getOrNull()

private const val ARTICLE_SCRIPT = """
(function(){
  try {
    var selectors = ['article','main','[role="main"]','.article','.post','.entry-content','.article-body'];
    var candidates = [];
    selectors.forEach(function(selector){
      document.querySelectorAll(selector).forEach(function(node){ candidates.push(node); });
    });
    if (!candidates.length && document.body) candidates.push(document.body);
    var source = candidates.sort(function(a,b){
      return (b.innerText || '').length - (a.innerText || '').length;
    })[0];
    if (!source) return JSON.stringify({available:false});
    var clean = source.cloneNode(true);
    clean.querySelectorAll('script,style,noscript,nav,header,footer,aside,form,button,svg,canvas,video,audio').forEach(function(n){n.remove();});
    var text = (clean.innerText || clean.textContent || '').replace(/\s+/g,' ').trim();
    var paragraphs = clean.querySelectorAll('p').length;
    var available = text.length >= 700 && (paragraphs >= 3 || text.length >= 2000);
    var author = '';
    var authorMeta = document.querySelector('meta[name="author"],meta[property="article:author"]');
    if (authorMeta) author = authorMeta.content || '';
    return JSON.stringify({
      available: available,
      title: document.title || '',
      author: author,
      language: document.documentElement.lang || '',
      text: text.slice(0,700000)
    });
  } catch(e) {
    return JSON.stringify({available:false});
  }
})()
"""
