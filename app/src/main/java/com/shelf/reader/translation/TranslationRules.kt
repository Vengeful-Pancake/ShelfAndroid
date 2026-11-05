package com.shelf.reader.translation

/** Literal, ordered rules. Legacy Name lines remain readable when upgrading. */
data class TranslationRule(val source: String, val target: String, val capitalize: Boolean = false) {
    fun line() = "\$[$source]=$target"

    companion object {
        fun parse(line: String): TranslationRule? {
            val value = line.trim().removePrefix("\uFEFF")
            if (value.startsWith('#')) return null
            val separator = if (value.startsWith("\$[")) value.indexOf("]=", 2) else value.indexOf('=')
            if (separator < 0) return null
            val source = value.substring(if (value.startsWith("\$[")) 2 else 0, separator).trim()
            val target = value.substring(separator + if (value.startsWith("\$[")) 2 else 1).trim()
            return if (source.isNotEmpty() && target.isNotEmpty()) TranslationRule(source, target) else null
        }
        fun parseAll(text: String) = text.lineSequence().mapNotNull(::parse).toList()
        fun canonical(text: String) = text.lineSequence().joinToString("\n") { parse(it)?.line() ?: it }
    }
}

data class TranslationToken(val text: String, val start: Int, val end: Int, val selectable: Boolean)

/** Source rules -> Smooth -> translated-text rules -> capitalization, with original offsets retained. */
class OrderedTranslation(
    private val dictionary: PhraseDictionary,
    global: List<TranslationRule>,
    story: List<TranslationRule> = emptyList(),
) {
    // A story definition replaces the corresponding global definition, including its capitalization.
    private val storySources = story.map { it.source }.toSet()
    private val rules = global.filter { it.source !in storySources } + story

    fun translate(source: String): String = process(source).text

    fun tokens(source: String, original: Boolean = false): List<TranslationToken> {
        val mapped = if (original) Mapped.initial(source) else process(source)
        val result = ArrayList<TranslationToken>()
        var index = 0
        while (index < mapped.text.length) {
            var end = index + 1
            val group = mapped.groups[index]
            if (group != 0) {
                while (end < mapped.text.length && mapped.groups[end] == group) end++
            } else if (han(mapped.text[index])) {
                end = index + (dictionary.match(mapped.text, index)?.first ?: 1)
                while (end > index + 1 && mapped.groups.sliceArray(index until end).any { it != 0 }) end--
            } else if (word(mapped.text[index])) {
                while (end < mapped.text.length && mapped.groups[end] == 0 && word(mapped.text[end]) && !han(mapped.text[end])) end++
            }
            val text = mapped.text.substring(index, end)
            result.add(TranslationToken(text, mapped.starts.sliceArray(index until end).min(),
                mapped.ends.sliceArray(index until end).max(), text.any { it.isLetterOrDigit() }))
            index = end
        }
        return result
    }

    private fun process(source: String): Mapped {
        var mapped = Mapped.initial(source)
        var serial = 1
        fun replace(rule: TranslationRule) {
            var found = mapped.text.indexOf(rule.source)
            if (found < 0) return
            val out = Builder(mapped.text.length)
            var cursor = 0
            // Each rule runs once, left to right. Its own output is never recursively re-matched.
            while (found >= 0) {
                out.slice(mapped, cursor, found)
                val end = found + rule.source.length
                val range = mapped.range(found, end)
                val capital = if (rule.capitalize) serial else mapped.caps.sliceArray(found until end).firstOrNull { it != 0 } ?: 0
                val target = rule.target
                val chineseToLatin = rule.source.any(::han) && target.none(::han)
                if (chineseToLatin && found > 0 && word(mapped.text[found - 1]) && word(target.first())) out.value(" ", range.first, range.second, 0, 0)
                out.value(target, range.first, range.second, serial++, capital)
                if (chineseToLatin && end < mapped.text.length && word(mapped.text[end]) && word(target.last())) out.value(" ", range.first, range.second, 0, 0)
                cursor = end
                found = mapped.text.indexOf(rule.source, cursor)
            }
            out.slice(mapped, cursor, mapped.text.length)
            mapped = out.build()
        }
        rules.filter { it.source.any(::han) }.forEach(::replace)
        fun smooth() {
            val translated = Builder(mapped.text.length)
            var index = 0
            var previousTranslated = false
            while (index < mapped.text.length) {
                val match = if (han(mapped.text[index])) dictionary.match(mapped.text, index) else null
                if (match != null) {
                    val end = index + match.first
                    val range = mapped.range(index, end)
                    if (translated.last()?.let { !it.isWhitespace() && it !in "([{«“‘" } == true) translated.value(" ", range.first, range.second, 0, 0)
                    translated.value(match.second, range.first, range.second, serial++, mapped.caps.sliceArray(index until end).firstOrNull { it != 0 } ?: 0)
                    previousTranslated = true
                    index = end
                } else {
                    val char = mapped.text[index]
                    if (previousTranslated && char.isLetterOrDigit()) translated.value(" ", mapped.starts[index], mapped.ends[index], 0, 0)
                    val punctuation = when (char) { '，', '、' -> ','; '。' -> '.'; '！' -> '!'; '？' -> '?'; '：' -> ':'; '；' -> ';'; '（' -> '('; '）' -> ')'; else -> char }
                    translated.value(punctuation.toString(), mapped.starts[index], mapped.ends[index], mapped.groups[index], mapped.caps[index])
                    previousTranslated = false
                    index++
                }
            }
            mapped = translated.build()
        }
        smooth()
        rules.filter { it.source.none(::han) }.forEach { rule ->
            replace(rule)
            // A translated-text rule may introduce Chinese again. Resolve it before the next rule.
            if (rule.target.any(::han)) smooth()
        }
        if (mapped.caps.none { it != 0 }) return mapped
        val capitalized = HashSet<Int>()
        val final = Builder(mapped.text.length)
        mapped.text.forEachIndexed { i, char ->
            val cap = mapped.caps[i]
            val value = if (cap != 0 && char.isLetter() && capitalized.add(cap)) char.titlecase(java.util.Locale.forLanguageTag("vi")) else char.toString()
            final.value(value, mapped.starts[i], mapped.ends[i], mapped.groups[i], cap)
        }
        return final.build()
    }

    private data class Mapped(val text: String, val starts: IntArray, val ends: IntArray, val groups: IntArray, val caps: IntArray) {
        fun range(from: Int, to: Int) = starts.sliceArray(from until to).min() to ends.sliceArray(from until to).max()
        companion object { fun initial(text: String) = Mapped(text, IntArray(text.length) { it }, IntArray(text.length) { it + 1 }, IntArray(text.length), IntArray(text.length)) }
    }
    private class Builder(capacity: Int) {
        private val text = StringBuilder(capacity)
        private var starts = IntArray(capacity.coerceAtLeast(16))
        private var ends = IntArray(starts.size)
        private var groups = IntArray(starts.size)
        private var caps = IntArray(starts.size)
        private fun ensure(size: Int) {
            require(size <= 2_000_000) { "Bản dịch quá dài; hãy kiểm tra quy tắc thay thế" }
            if (size > starts.size) {
            val next = maxOf(size, starts.size * 2)
            starts = starts.copyOf(next); ends = ends.copyOf(next); groups = groups.copyOf(next); caps = caps.copyOf(next)
        } }
        fun last() = text.lastOrNull()
        fun value(value: String, start: Int, end: Int, group: Int, cap: Int) {
            val offset = text.length; ensure(offset + value.length); text.append(value)
            java.util.Arrays.fill(starts, offset, text.length, start); java.util.Arrays.fill(ends, offset, text.length, end)
            java.util.Arrays.fill(groups, offset, text.length, group); java.util.Arrays.fill(caps, offset, text.length, cap)
        }
        fun slice(value: Mapped, from: Int, to: Int) {
            val offset = text.length; ensure(offset + to - from); text.append(value.text, from, to)
            value.starts.copyInto(starts, offset, from, to); value.ends.copyInto(ends, offset, from, to)
            value.groups.copyInto(groups, offset, from, to); value.caps.copyInto(caps, offset, from, to)
        }
        fun build() = Mapped(text.toString(), starts.copyOf(text.length), ends.copyOf(text.length), groups.copyOf(text.length), caps.copyOf(text.length))
    }
    companion object {
        private fun han(char: Char) = char in '\u3400'..'\u9fff' || char in '\uf900'..'\ufaff'
        private fun word(char: Char) = char.isLetterOrDigit() || Character.getType(char) == Character.NON_SPACING_MARK.toInt() || Character.getType(char) == Character.COMBINING_SPACING_MARK.toInt()
    }
}
