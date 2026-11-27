package com.shelf.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSearchTest {
    @Test fun indexesMultipleMatchesAndPhrasesAcrossInlineFormatting() {
        val text = ReaderSearch.text("<p>Old <i>library</i>.</p><p>Old&nbsp;<b>library</b>.</p>")
        assertEquals("Old library. Old library.", text)
        assertEquals(listOf(0, 13), ReaderSearch.hits(text, "old library").toList())
    }
}
