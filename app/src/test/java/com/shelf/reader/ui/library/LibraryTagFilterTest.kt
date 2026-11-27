package com.shelf.reader.ui.library

import com.shelf.reader.data.tagKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTagFilterTest {
    @Test fun allAndAnyHandleMultipleIncludesAndExclusionTakesPrecedence() {
        val filter = LibraryTagFilter()
            .toggle("Travel", TagRule.INCLUDE)
            .toggle("Adventure", TagRule.INCLUDE)
            .toggle("Horror", TagRule.EXCLUDE)

        assertTrue(filter.matches(setOf("travel", "adventure")))
        assertFalse(filter.matches(setOf("travel")))
        assertFalse(filter.matches(setOf("travel", "adventure", "horror")))

        val any = filter.copy(match = TagMatch.ANY)
        assertTrue(any.matches(setOf("travel")))
        assertTrue(any.matches(setOf("adventure")))
        assertFalse(any.matches(setOf("adventure", "horror")))
        assertFalse(any.matches(emptySet()))
    }

    @Test fun selectingTheOtherRuleReplacesItAndExclusionOnlyKeepsUntaggedBooks() {
        val included = LibraryTagFilter().toggle("Phiêu lưu", TagRule.INCLUDE)
        val decomposedName = "Phie\u0302u lu\u031bu"
        assertTrue(included.matches(setOf(tagKey(decomposedName))))

        val excluded = included.toggle(decomposedName.uppercase(), TagRule.EXCLUDE)
        assertTrue(excluded.included.isEmpty())
        assertTrue(excluded.matches(emptySet()))
        assertFalse(excluded.matches(setOf(tagKey("Phiêu lưu"))))
        assertFalse(excluded.toggle("phiêu lưu", TagRule.EXCLUDE).isActive)
    }
}
