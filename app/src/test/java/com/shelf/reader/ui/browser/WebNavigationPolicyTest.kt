package com.shelf.reader.ui.browser

import org.junit.Assert.*
import org.junit.Test

class WebNavigationPolicyTest {
    @Test fun translationRequiresTheCurrentHttpsMainFrame() {
        assertEquals(setOf("*"), WebNavigationPolicy.translationOriginRules)
        assertTrue(WebNavigationPolicy.acceptsTranslationOrigin("https://example.com", "https://example.com/chapter/1", true))
        assertTrue(WebNavigationPolicy.acceptsTranslationOrigin("https://example.com:443", "https://example.com/", true))
        assertFalse(WebNavigationPolicy.acceptsTranslationOrigin("http://example.com", "https://example.com/", true))
        assertFalse(WebNavigationPolicy.acceptsTranslationOrigin("https://attacker.test", "https://example.com/", true))
        assertFalse(WebNavigationPolicy.acceptsTranslationOrigin("https://example.com", "https://example.com/", false))
        assertFalse(WebNavigationPolicy.acceptsTranslationOrigin("https://example.com:8443", "https://example.com/", true))
        assertFalse(WebNavigationPolicy.acceptsTranslationOrigin("null", "https://example.com/", true))
        assertFalse(WebNavigationPolicy.acceptsTranslationOrigin("https://example.com", null, true))
    }

    @Test fun onlyHttpsPagesMayLoad() {
        assertTrue(WebNavigationPolicy.isSecureWebUrl("https://sangtacviet.app/truyen/"))
        for (url in listOf("http://example.com", "file:///sdcard/book", "content://private/data", "javascript:alert(1)",
            "intent://example.com#Intent;end", "https://user:secret@example.com", "https:///path")) {
            assertFalse(url, WebNavigationPolicy.isSecureWebUrl(url))
        }
    }

    @Test fun websitesCannotSilentlyLaunchOtherApps() {
        assertTrue(WebNavigationPolicy.mayOpenExternal("mailto:reader@example.com", true, true))
        assertFalse(WebNavigationPolicy.mayOpenExternal("mailto:reader@example.com", true, false))
        assertFalse(WebNavigationPolicy.mayOpenExternal("tel:123", false, true))
        assertFalse(WebNavigationPolicy.mayOpenExternal("intent://install#Intent;end", true, true))
        assertFalse(WebNavigationPolicy.mayOpenExternal("content://private/data", true, true))
    }
}
