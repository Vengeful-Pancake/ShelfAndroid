package com.shelf.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterKeysTest {
    @Test fun headingWinsWhenAnExportReusesOrdinalFilenames() {
        val old = listOf("1.xhtml\u0000chapter one", "2.xhtml\u0000chapter two")
        val updated = listOf("1.xhtml\u0000prologue", "2.xhtml\u0000chapter one", "3.xhtml\u0000chapter two")
        assertEquals(mapOf(0 to 1, 1 to 2), chapterMoves(old, updated))
    }
}
