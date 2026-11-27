package com.shelf.reader.ui.library

import com.shelf.reader.data.tagKey
import com.shelf.reader.data.SmartShelfEntity

enum class TagMatch { ALL, ANY }

internal enum class TagRule(val label: String) { INCLUDE("Include"), EXCLUDE("Exclude") }

data class LibraryTagFilter(
    val included: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
    val match: TagMatch = TagMatch.ALL,
) {
    val size: Int get() = included.size + excluded.size
    val isActive: Boolean get() = size != 0

    internal fun toggle(name: String, rule: TagRule): LibraryTagFilter {
        val key = tagKey(name)
        return when (rule) {
            TagRule.INCLUDE -> copy(included = if (key in included) included - key else included + key, excluded = excluded - key)
            TagRule.EXCLUDE -> copy(included = included - key, excluded = if (key in excluded) excluded - key else excluded + key)
        }
    }

    /** Both the indexed book tags and selected tags use canonical keys. */
    internal fun matches(keys: Set<String>): Boolean {
        if (excluded.any { it in keys }) return false
        if (included.isEmpty()) return true
        return when (match) {
            TagMatch.ALL -> keys.containsAll(included)
            TagMatch.ANY -> included.any { it in keys }
        }
    }
}

internal fun SmartShelfEntity.tagFilter() = LibraryTagFilter(included.toSet(), excluded.toSet(), if (matchAny) TagMatch.ANY else TagMatch.ALL)

internal fun SmartShelfEntity.withTags(filter: LibraryTagFilter) = copy(
    included = filter.included.toList(), excluded = filter.excluded.toList(), matchAny = filter.match == TagMatch.ANY,
)
