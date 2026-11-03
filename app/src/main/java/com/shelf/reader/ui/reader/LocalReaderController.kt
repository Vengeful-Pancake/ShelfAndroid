package com.shelf.reader.ui.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.shelf.reader.ShelfApp
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.translation.ChineseTranslator
import com.shelf.reader.translation.NameStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Attached only to generated local reader documents, never Internet pages. */
class LocalReaderController(context: Context, private val story: String, private var title: String) {
    private val app = context.applicationContext
    private val names = NameStore.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var preferences = ReaderSettings()
    private var web: WebView? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var generation = 0
    private var disposed = false
    private val css = app.assets.open("stv-tools.css").bufferedReader().use { it.readText() }
    private val script = app.assets.open("stv-tools.js").bufferedReader().use { it.readText() }

    init {
        scope.launch {
            withContext(Dispatchers.IO) { names.activate(story, title) }
            ShelfApp.repository(app).settings.flow.collect { preferences = it; configure() }
        }
        scope.launch { names.revision.collect { configure(); evaluate("if(window.stvRefresh)window.stvRefresh()") } }
    }

    fun attach(value: WebView) { web = value; value.addJavascriptInterface(this, "LocalReader") }
    fun updateTitle(value: String) { if (value.isNotBlank() && value != title) { title = value; scope.launch { withContext(Dispatchers.IO) { names.activate(story, title) }; configure() } } }
    fun install() {
        evaluate("(function(){if(!document.getElementById('stv-style')){const s=document.createElement('style');s.id='stv-style';s.textContent=${JSONObject.quote(css)};document.head.appendChild(s);}})();$script")
        configure()
        evaluate("if(window.stvRefresh)window.stvRefresh()")
    }
    private fun configure() = evaluate("if(window.stvConfigure)window.stvConfigure(${config()})")
    private fun evaluate(js: String) { web?.takeIf { !disposed }?.post { if (!disposed) web?.evaluateJavascript(js, null) } }
    @JavascriptInterface fun config(): String {
        val saved = names.appearance()
        val settings = preferences
        val defaultPage = settings.background == ReaderSettings().background
        if (!saved.has("background")) saved.put("background", if (defaultPage) "#1b1b1b" else settings.background)
        if (!saved.has("foreground")) saved.put("foreground", if (defaultPage) "#d4d4d4" else settings.foreground)
        if (!saved.has("size")) saved.put("size", settings.textSize)
        if (!saved.has("line")) saved.put("line", settings.lineHeight)
        if (!saved.has("font")) saved.put("font", if (settings.font.name == "MONO") "monospace" else "Helvetica")
        return JSONObject().put("title", title).put("appearance", saved).put("original", !settings.translationEnabled).toString()
    }
    @JavascriptInterface fun names(): String = JSONObject().put("global", names.global(preferences.globalNames)).put("story", names.story(story)).toString()
    @JavascriptInterface fun render(text: String, original: Boolean): String {
        require(text.length <= 700_000)
        return ChineseTranslator(app, preferences, story).annotated(text, original)
    }
    @JavascriptInterface fun describe(text: String): String {
        require(text.length <= 200)
        return ChineseTranslator(app, preferences, story).description(text).toString()
    }
    private fun result(action: () -> Unit): String = try { action(); JSONObject().put("ok", true).toString() }
        catch (error: Exception) { JSONObject().put("ok", false).put("error", error.message ?: "Không thể lưu").toString() }
    @JavascriptInterface fun saveRules(scopeName: String, text: String): String = result {
        require(scopeName in listOf("global", "story")); names.save(story, scopeName == "global", text)
    }
    @JavascriptInterface fun saveName(scopeName: String, source: String, target: String): String = result {
        require(scopeName in listOf("global", "story")); names.add(story, scopeName == "global", source, target, preferences.globalNames)
    }
    @JavascriptInterface fun saveAppearance(json: String): String = result {
        require(json.length <= 4096); names.saveAppearance(JSONObject(json))
    }
    @JavascriptInterface fun speak(text: String, index: Int, speed: Double, voice: String, language: String) {
        if (text.length > 2000 || index < 0 || speed !in .5..2.5 || language !in listOf("zh-CN", "vi-VN")) return
        scope.launch {
            val speakNow = {
                val engine = tts
                if (engine != null) {
                    generation++
                    val locale = Locale.forLanguageTag(language)
                    val available = engine.setLanguage(locale)
                    if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
                        evaluate("window.stvSpeechError('Hãy cài giọng đọc tiếng Việt/Trung trong cài đặt Android')")
                    } else {
                        engine.voices?.find { it.name == voice && it.locale.language == locale.language }?.let { engine.voice = it }
                        engine.setSpeechRate(speed.toFloat())
                        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "$generation:$index")
                    }
                }
            }
            if (tts == null) {
                pending = speakNow
                tts = TextToSpeech(app) { status -> scope.launch {
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        evaluate("window.stvVoiceList(${JSONArray(tts?.voices?.filter { it.locale.language in listOf("vi", "zh") }?.map { it.name }?.sorted().orEmpty())})")
                        pending?.invoke(); pending = null
                    } else { pending = null; evaluate("window.stvSpeechError('Thiết bị chưa có bộ đọc văn bản')") }
                } }.also { engine -> engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { scope.launch {
                        val parts = id.orEmpty().split(':')
                        if (parts.getOrNull(0)?.toIntOrNull() == generation) parts.getOrNull(1)?.toIntOrNull()?.let { evaluate("window.stvSpeechDone($it)") }
                    } }
                    @Deprecated("Legacy TTS callback") override fun onError(id: String?) { evaluate("window.stvSpeechError('Giọng đọc gặp lỗi')") }
                }) }
            } else if (ready) speakNow() else pending = speakNow
        }
    }
    @JavascriptInterface fun stop() { scope.launch { generation++; pending = null; tts?.stop() } }
    fun close() { disposed = true; pending = null; generation++; tts?.stop(); tts?.shutdown(); tts = null; web = null; scope.cancel() }
}
