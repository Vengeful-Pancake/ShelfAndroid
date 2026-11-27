package com.shelf.reader.epub

import java.text.Normalizer

internal fun EpubBook.chapterKeys(): List<String> = spine.mapIndexed { index, path ->
    val label = tocLabelFor(index).takeUnless { it == "Section ${index + 1}" }.orEmpty()
    val normalized = Normalizer.normalize(label.trim(), Normalizer.Form.NFC).lowercase()
    "$path\u0000$normalized"
}

/** Prefer a unique chapter heading because an export can reuse filenames by their new ordinal. */
internal fun chapterMoves(old: List<String>, current: List<String>): Map<Int, Int> {
    if (old.isEmpty() || current.isEmpty() || old == current) return emptyMap()
    fun label(key: String) = key.substringAfter('\u0000', "")
    val oldCounts = old.groupingBy(::label).eachCount()
    val labels = current.indices.groupBy { label(current[it]) }
    val paths = current.indices.groupBy { current[it].substringBefore('\u0000') }
    return buildMap {
        old.forEachIndexed { index, key ->
            val heading = label(key)
            val byHeading = labels[heading]?.singleOrNull()?.takeIf { heading.isNotEmpty() && oldCounts[heading] == 1 }
            val byPath = paths[key.substringBefore('\u0000')]?.singleOrNull()?.takeIf {
                val newHeading = label(current[it])
                heading.isEmpty() || newHeading.isEmpty() || newHeading == heading
            }
            val destination = byHeading ?: byPath ?: index.coerceAtMost(current.lastIndex)
            if (destination != index) put(index, destination)
        }
    }
}
