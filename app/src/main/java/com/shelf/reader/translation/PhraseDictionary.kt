package com.shelf.reader.translation

/** Port of TangThu's deterministic CJK longest-phrase matcher. Immutable after loading. */
class PhraseDictionary(private val mapping: Map<String, String>) {
    private val lengths = run {
        val buckets = HashMap<Char, MutableSet<Int>>()
        mapping.keys.forEach { key -> if (key.isNotEmpty()) buckets.getOrPut(key.first()) { HashSet() }.add(key.length) }
        buckets.mapValues { (_, sizes) -> sizes.sortedDescending() }
    }

    fun match(text: String, index: Int): Pair<Int, String>? {
        for (size in lengths[text[index]].orEmpty()) {
            if (index + size <= text.length) {
                mapping[text.substring(index, index + size)]?.let { return size to it }
            }
        }
        return null
    }

    fun translate(text: String, overrides: PhraseDictionary? = null, cjkOnly: Boolean = false): String {
        val out = StringBuilder()
        var index = 0
        var previousTranslated = false
        while (index < text.length) {
            val han = text[index] in '\u3400'..'\u9fff' || text[index] in '\uf900'..'\ufaff'
            val match = if (!cjkOnly || han) overrides?.match(text, index) ?: match(text, index) else null
            if (match != null) {
                if (out.isNotEmpty() && !out.last().isWhitespace() && out.last() !in "([{«“‘") out.append(' ')
                out.append(match.second)
                index += match.first
                previousTranslated = true
            } else {
                val char = text[index++]
                if (previousTranslated && char.isLetterOrDigit()) out.append(' ')
                out.append(when (char) {
                    '。' -> '.'; '，', '、' -> ','; '！' -> '!'; '？' -> '?'; '：' -> ':'; '；' -> ';'
                    '（' -> '('; '）' -> ')'; '【' -> '['; '】' -> ']'; '《' -> '«'; '》' -> '»'
                    else -> char
                })
                previousTranslated = false
            }
        }
        return out.toString().replace(Regex("[ \\t]+([,.;:!?%\\)\\]\\}»”’])"), "$1")
            .replace(Regex("([\\(\\[\\{«“‘])[ \\t]+"), "$1")
            .replace(Regex("[ \\t]{2,}"), " ")
    }

    companion object {
        fun parse(input: Sequence<String>, firstMeaning: Boolean = false): Map<String, String> = buildMap {
            input.forEach { raw ->
                val line = raw.trim().removePrefix("\uFEFF")
                if (!line.startsWith('#') && '=' in line) {
                    val source = line.substringBefore('=').trim()
                    var target = line.substringAfter('=').trim()
                    if (firstMeaning) {
                        for (separator in listOf("/", "|", "；", ";")) {
                            val first = target.substringBefore(separator).trim()
                            if (first.isNotEmpty()) target = first
                        }
                    }
                    if (source.isNotEmpty() && target.isNotEmpty()) put(source, target)
                }
            }
        }
    }
}
