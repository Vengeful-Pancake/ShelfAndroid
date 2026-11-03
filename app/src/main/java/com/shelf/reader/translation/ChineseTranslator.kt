package com.shelf.reader.translation

import android.content.Context
import com.shelf.reader.data.ReaderSettings
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/** Offline Smooth Xianxia / QuickTranslator shared by EPUBs and the browser. Call off the UI thread. */
class ChineseTranslator(context: Context, private val settings: ReaderSettings, private val storyKey: String = "") {
    private val app = context.applicationContext
    private val custom = PhraseDictionary(PhraseDictionary.parse(
        (settings.globalTerms + "\n" + NameStore.get(app).global(settings.globalNames) + "\n" + NameStore.get(app).story(storyKey)).lineSequence()))

    fun annotated(text: String, original: Boolean = false): String {
        if (!containsChinese(text)) return escape(text)
        val base = bundled(app)
        val hv = phonetic(app)
        val out = StringBuilder()
        var index = 0
        var previousTranslated = false
        while (index < text.length) {
            val match = if (containsChinese(text[index].toString())) custom.match(text, index) ?: base.match(text, index) else null
            if (match != null || containsChinese(text[index].toString())) {
                val size = match?.first ?: 1
                val source = text.substring(index, index + size)
                val rendered = match?.second ?: source
                if (!original && out.isNotEmpty() && previousTranslated) out.append(' ')
                val smooth = base.translate(source, cjkOnly = true)
                out.append("<span class=\"stv-word\" data-src=\"").append(escape(source))
                    .append("\" data-hv=\"").append(escape(hv.translate(source, cjkOnly = true)))
                    .append("\" data-smooth=\"").append(escape(smooth)).append("\">")
                    .append(escape(if (original) source else rendered)).append("</span>")
                previousTranslated = true
                index += size
            } else {
                val char = text[index++]
                out.append(escape(if (original) char.toString() else when (char) {
                    '，' -> ", "; '。' -> ". "; '！' -> "! "; '？' -> "? "; '：' -> ": "; '；' -> "; "; else -> char.toString()
                }))
                previousTranslated = false
            }
        }
        return out.toString()
    }

    fun description(text: String): org.json.JSONObject = org.json.JSONObject().put("source", text)
        .put("hv", phonetic(app).translate(text, cjkOnly = true)).put("smooth", bundled(app).translate(text, cjkOnly = true))

    fun interactiveHtml(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        val nodes = ArrayList<TextNode>()
        doc.body().traverse { node, _ -> if (node is TextNode && containsChinese(node.wholeText) &&
            node.parent()?.nodeName() !in listOf("script", "style")) nodes.add(node) }
        nodes.forEach { node ->
            val source = node.wholeText
            val wrapper = org.jsoup.nodes.Element("span").addClass("stv-block").attr("data-original", source)
            wrapper.html(annotated(source, !settings.translationEnabled))
            node.replaceWith(wrapper)
        }
        return doc.body().html()
    }

    fun plain(text: String): String {
        if (!settings.translationEnabled || !containsChinese(text)) return text
        return bundled(app).translate(text, custom, cjkOnly = true)
    }

    fun html(html: String): String {
        if (!settings.translationEnabled || !containsChinese(html)) return html
        val doc = Jsoup.parseBodyFragment(html)
        doc.body().traverse { node, _ ->
            if (node is TextNode && node.parent()?.nodeName() !in listOf("script", "style")) {
                node.text(plain(node.wholeText))
            }
        }
        return doc.body().html()
    }

    companion object {
        fun escape(text: String): String = org.jsoup.nodes.TextNode(text).outerHtml().replace("\"", "&quot;")
        @Volatile private var phonetics: PhraseDictionary? = null
        private fun phonetic(context: Context): PhraseDictionary = phonetics ?: synchronized(this) {
            phonetics ?: context.assets.open("dictionaries/ChinesePhienAmWords.txt").bufferedReader().use {
                PhraseDictionary(PhraseDictionary.parse(it.lineSequence(), true)).also { phonetics = it }
            }
        }
        fun containsChinese(text: String) = text.any { it in '\u3400'..'\u9fff' || it in '\uf900'..'\ufaff' }
        @Volatile private var dictionary: PhraseDictionary? = null
        private fun bundled(context: Context): PhraseDictionary = dictionary ?: synchronized(this) {
            dictionary ?: run {
                val mapping = HashMap<String, String>()
                // Same priority as TangThu's QT engine: later files overwrite earlier ones.
                for (file in listOf("ChinesePhienAmWords.txt", "VietPhrase.compact.txt", "Names.txt", "Names2.txt", "VietPhraseOverride.txt")) {
                    context.assets.open("dictionaries/$file").bufferedReader(Charsets.UTF_8).use {
                        mapping.putAll(PhraseDictionary.parse(it.lineSequence(), firstMeaning = true))
                    }
                }
                context.assets.open("dictionaries/IgnoredChinesePhrases.txt").bufferedReader().use { reader ->
                    reader.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
                        .forEach { mapping[it] = it }
                }
                PhraseDictionary(mapping).also { dictionary = it }
            }
        }
    }
}
