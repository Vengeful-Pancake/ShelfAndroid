package com.shelf.reader.translation

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.URI

/** One atomic, private JSON document shared by the reader and Dictionary tab. */
class NameStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "translation-names.json"))
    private var root = if (file.baseFile.exists()) JSONObject(file.openRead().bufferedReader().use { it.readText() }) else JSONObject()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    @Synchronized fun global(fallback: String = "") = TranslationRule.canonical(root.optString("global", fallback))
    @Synchronized fun story(key: String) = TranslationRule.canonical(root.optJSONObject("stories")?.optString(key).orEmpty())
    @Synchronized fun rules(key: String, global: Boolean, fallback: String = ""): List<TranslationRule> {
        val text = if (global) global(fallback) else story(key)
        val capitalized = root.optJSONObject("capitalized")?.optJSONObject(if (global) "global" else "story:$key")
        return TranslationRule.parseAll(text).map { it.copy(capitalize = capitalized?.optString(it.source) == it.target) }
    }
    @Synchronized fun activeKey() = root.optString("activeStory")
    @Synchronized fun activeTitle() = root.optString("activeTitle", "Truyện đang đọc")
    @Synchronized fun activate(key: String, title: String) {
        if (key == activeKey() && title == activeTitle()) return
        commit(JSONObject(root.toString()).put("activeStory", key).put("activeTitle", title))
    }
    @Synchronized fun save(key: String, global: Boolean, rules: String) {
        require(rules.length <= 1_000_000) { "Bảng Name quá lớn" }
        rules.lineSequence().filter { it.isNotBlank() && !it.trim().startsWith('#') }.forEach {
            require(TranslationRule.parse(it) != null) { "Cú pháp: \$[cụm từ]=bản dịch" }
        }
        val next = JSONObject(root.toString()).put("version", 2)
        val canonical = TranslationRule.canonical(rules)
        if (global) next.put("global", canonical) else {
            require(key.isNotBlank()) { "Hãy mở truyện trước" }
            next.put("stories", (next.optJSONObject("stories") ?: JSONObject()).put(key, canonical))
        }
        commit(next)
    }
    @Synchronized fun add(key: String, global: Boolean, source: String, target: String, fallback: String, capitalize: Boolean = false) {
        require(source.isNotBlank() && target.isNotBlank() && source.length <= 200 && target.length <= 1000)
        require(!source.contains('\n') && !source.contains("]=") && !target.contains('\n'))
        val lines = (if (global) global(fallback) else story(key)).lines().toMutableList()
        val existing = lines.indexOfFirst { TranslationRule.parse(it)?.source == source }
        val rule = TranslationRule(source, target).line()
        if (existing >= 0) {
            lines[existing] = rule
            for (i in lines.lastIndex downTo existing + 1) if (TranslationRule.parse(lines[i])?.source == source) lines.removeAt(i)
        } else lines.add(rule)
        // Store the capitalization intent separately so it runs after all translation rules.
        val next = JSONObject(root.toString()).put("version", 2)
        val table = lines.filter { it.isNotBlank() }.joinToString("\n")
        require(table.length <= 1_000_000) { "Bảng Name quá lớn" }
        if (global) next.put("global", table) else {
            require(key.isNotBlank()) { "Hãy mở truyện trước" }
            next.put("stories", (next.optJSONObject("stories") ?: JSONObject()).put(key, table))
        }
        val caps = next.optJSONObject("capitalized") ?: JSONObject()
        val capScope = if (global) "global" else "story:$key"
        val entries = caps.optJSONObject(capScope) ?: JSONObject()
        if (capitalize) entries.put(source, target) else entries.remove(source)
        next.put("capitalized", caps.put(capScope, entries))
        commit(next)
    }
    @Synchronized fun appearance(): JSONObject = JSONObject(root.optJSONObject("appearance")?.toString() ?: "{}")
    @Synchronized fun snapshot(): JSONObject = JSONObject(root.toString())
    @Synchronized fun restore(value: JSONObject) {
        require(value.toString().length <= 4_000_000) { "JSON Name quá lớn" }
        fun validate(text: String) {
            require(text.length <= 1_000_000)
            text.lineSequence().filter { it.isNotBlank() && !it.trim().startsWith('#') }.forEach {
                require(TranslationRule.parse(it) != null)
            }
        }
        validate(value.optString("global"))
        value.optJSONObject("stories")?.let { stories -> stories.keys().forEach { validate(stories.getString(it)) } }
        value.optJSONObject("appearance")?.let { validateAppearance(it) }
        commit(JSONObject(value.toString()))
    }
    @Synchronized fun saveAppearance(value: JSONObject) {
        validateAppearance(value)
        commit(JSONObject(root.toString()).put("appearance", value))
    }
    private fun validateAppearance(value: JSONObject) {
        require(value.toString().length < 4096)
        for (key in listOf("background", "foreground", "shadowColor")) {
            if (value.has(key)) require(value.getString(key).matches(Regex("#[0-9a-fA-F]{6}")))
        }
        require(value.optInt("size", 19) in 12..40)
        require(value.optDouble("line", 1.7) in 1.0..3.0)
        require(value.optString("font", "sans-serif") in listOf("sans-serif", "serif", "monospace", "Arial", "Helvetica", "Tahoma", "Times New Roman"))
        require(value.optString("align", "left") in listOf("left", "center", "right", "justify"))
    }
    private fun commit(next: JSONObject) {
        val output = file.startWrite()
        try { output.write(next.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        root = next
        changes.value++
    }
    companion object {
        @Volatile private var instance: NameStore? = null
        fun get(context: Context): NameStore = instance ?: synchronized(this) {
            instance ?: NameStore(context.applicationContext).also { instance = it }
        }
        fun storyKey(url: String): String = runCatching {
            val uri = URI(url)
            val parts = uri.path.orEmpty().trim('/').split('/')
            val bookParam = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0].lowercase() in listOf("bookid", "book_id", "bid", "novelid") }
            val path = when {
                parts.firstOrNull() == "truyen" && parts.size >= 4 -> parts.take(4).joinToString("/")
                bookParam != null -> "book/${bookParam[1]}"
                else -> uri.path.orEmpty().substringBeforeLast('/', "")
            }
            val host = uri.host.orEmpty().lowercase().let { if (it in listOf("sangtacviet.app", "sangtacviet.com")) "sangtacviet" else it }
            "$host/$path"
        }.getOrDefault(url.substringBefore('?').substringBefore('#'))
    }
}
