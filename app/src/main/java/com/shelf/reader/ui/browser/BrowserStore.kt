package com.shelf.reader.ui.browser

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val BROWSER_HOME = "https://sangtacviet.app/"
data class BrowserTab(val id: String = UUID.randomUUID().toString(), val url: String = BROWSER_HOME,
    val title: String = "New tab", val scrollX: Int = 0, val scrollY: Int = 0)
data class BrowserVisit(val id: String = UUID.randomUUID().toString(), val url: String, val title: String, val time: Long = System.currentTimeMillis())
data class BrowserFavorite(val url: String, val title: String)
data class BrowserData(val tabs: List<BrowserTab> = listOf(BrowserTab()), val activeId: String = tabs.first().id,
    val history: List<BrowserVisit> = emptyList(), val favorites: List<BrowserFavorite> = emptyList()) {
    val active get() = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
    fun open(url: String = BROWSER_HOME): BrowserData {
        require(tabs.size < 20) { "Close a tab before opening more (20 maximum)" }
        require(WebNavigationPolicy.isSecureWebUrl(url) && url.length <= 8192)
        val tab = BrowserTab(url = url)
        return copy(tabs = tabs + tab, activeId = tab.id)
    }
    fun close(id: String): BrowserData {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = tabs.filterNot { it.id == id }.ifEmpty { listOf(BrowserTab()) }
        return copy(tabs = remaining, activeId = if (activeId == id) remaining[index.coerceAtMost(remaining.lastIndex)].id else activeId)
    }
    fun visit(url: String, title: String): BrowserData {
        if (!WebNavigationPolicy.isSecureWebUrl(url) || url.length > 8192) return this
        val entry = BrowserVisit(url = url.take(8192), title = title.take(300))
        return copy(history = (listOf(entry) + history.dropWhile { it.url == url }).take(1000))
    }
}

/** Only browsing metadata is persisted. WebView state and cookies remain in Android's private storage. */
class BrowserStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "browser.json"))
    private val writes = Channel<BrowserData>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    init { CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        for (value in writes) {
            runCatching {
                val out = file.startWrite()
                try { out.write(encode(value).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
                catch (error: Exception) { file.failWrite(out); throw error }
            }.onFailure { android.util.Log.e("ShelfBrowser", "Could not save browsing data", it) }
        }
    } }
    @Synchronized fun update(change: (BrowserData) -> BrowserData) {
        val next = change(mutable.value)
        if (next == mutable.value) return
        mutable.value = next; writes.trySend(next)
    }
    fun tab(id: String, change: (BrowserTab) -> BrowserTab) = update { data -> data.copy(tabs = data.tabs.map { if (it.id == id) change(it) else it }) }
    fun select(id: String) = update { if (it.tabs.any { tab -> tab.id == id }) it.copy(activeId = id) else it }
    fun favorite(url: String, title: String) = update { data ->
        require(WebNavigationPolicy.isSecureWebUrl(url) && url.length <= 8192)
        if (data.favorites.any { it.url == url }) data.copy(favorites = data.favorites.filterNot { it.url == url })
        else { require(data.favorites.size < 1000) { "Favorites limit reached" }; data.copy(favorites = data.favorites + BrowserFavorite(url.take(8192), title.take(300))) }
    }
    private fun read(): BrowserData = runCatching {
        if (!file.baseFile.exists()) return BrowserData()
        val text = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16_384)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 24_000_000); output.write(buffer, 0, n) }
            output.toString("UTF-8")
        }
        decode(JSONObject(text))
    }.getOrElse { BrowserData() }
    companion object {
        @Volatile private var instance: BrowserStore? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: BrowserStore(context.applicationContext).also { instance = it } }
        internal fun encode(data: BrowserData) = JSONObject().put("version", 1).put("activeId", data.activeId)
            .put("tabs", JSONArray(data.tabs.map { JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("x", it.scrollX).put("y", it.scrollY) }))
            .put("history", JSONArray(data.history.map { JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("time", it.time) }))
            .put("favorites", JSONArray(data.favorites.map { JSONObject().put("url", it.url).put("title", it.title) }))
        internal fun decode(json: JSONObject): BrowserData {
            fun rows(key: String, max: Int): List<JSONObject> { val array = json.optJSONArray(key) ?: return emptyList(); return (0 until minOf(array.length(), max)).mapNotNull { array.optJSONObject(it) }.filter { WebNavigationPolicy.isSecureWebUrl(it.optString("url")) && it.optString("url").length <= 8192 } }
            val tabs = rows("tabs", 20).map { BrowserTab(it.optString("id").takeIf { id -> id.length in 1..80 } ?: UUID.randomUUID().toString(), it.getString("url"), it.optString("title").take(300), it.optInt("x").coerceAtLeast(0), it.optInt("y").coerceAtLeast(0)) }.distinctBy { it.id }.ifEmpty { listOf(BrowserTab()) }
            return BrowserData(tabs, json.optString("activeId").takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id,
                rows("history", 1000).map { BrowserVisit(it.optString("id", UUID.randomUUID().toString()), it.getString("url"), it.optString("title").take(300), it.optLong("time")) },
                rows("favorites", 1000).map { BrowserFavorite(it.getString("url"), it.optString("title").take(300)) }.distinctBy { it.url })
        }
    }
}
