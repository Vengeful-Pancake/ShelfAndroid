package com.shelf.reader.ui.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserDataTest {
    @Test fun closingActiveTabSelectsANeighborAndClosingLastCreatesHome() {
        val first = BrowserData()
        val second = first.open("https://example.com/book")
        assertEquals(second.tabs.last().id, second.activeId)
        val closed = second.close(second.activeId)
        assertEquals(first.activeId, closed.activeId)
        val empty = closed.close(closed.activeId)
        assertEquals(1, empty.tabs.size); assertEquals(BROWSER_HOME, empty.active.url)
    }
    @Test fun closingBackgroundTabPreservesActiveSelection() {
        val data = BrowserData().open("https://example.com/1").open("https://example.com/2")
        assertEquals(data.activeId, data.close(data.tabs.first().id).activeId)
    }
    @Test fun tabsRejectUnsafeTargetsAndHaveABoundedLimit() {
        for (url in listOf("javascript:alert(1)", "file:///data/local/private", "https://user:pass@example.com/")) {
            assertThrows(IllegalArgumentException::class.java) { BrowserData().open(url) }
        }
        var data = BrowserData()
        repeat(19) { data = data.open() }
        assertThrows(IllegalArgumentException::class.java) { data.open() }
    }
    @Test fun historyKeepsRevisitsButCollapsesReloadsAndStaysBounded() {
        var data = BrowserData().visit("https://example.com/a", "A").visit("https://example.com/a", "Updated")
        assertEquals(1, data.history.size); assertEquals("Updated", data.history.first().title)
        data = data.visit("https://example.com/b", "B").visit("https://example.com/a", "A")
        assertEquals(3, data.history.size)
        repeat(1001) { data = data.visit("https://example.com/$it", "$it") }
        assertEquals(1000, data.history.size)
        assertEquals("https://example.com/1000", data.history.first().url)
    }
}
