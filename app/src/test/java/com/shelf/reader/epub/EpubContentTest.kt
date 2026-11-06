package com.shelf.reader.epub

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class EpubContentTest {
    private fun clean(html: String) = Jsoup.parseBodyFragment(EpubContent.sanitize(Jsoup.parse(html), "book/ch1.xhtml",
        { "https://shelf.localhost/res/$it" }, { if (it == "book/ch2.xhtml") 1 else 0 }))

    @Test fun hostileBookCannotSupplyActiveElementsOrTrustedReaderAttributes() {
        val result = clean("""<body onload="LocalReader.saveRules('global','bad')">
            <script>LocalReader.saveRules('global','bad')</script>
            <iframe srcdoc="&lt;script&gt;evil()&lt;/script&gt;"></iframe>
            <form action="javascript:evil()"><input autofocus onfocus="evil()"><button formaction="https://evil.test">Readable text</button></form>
            <svg><a href="javascript:evil()"><animate attributeName="href" to="javascript:evil()"/>text</a></svg>
            <math><mtext><img src="bad" onerror="evil()"></mtext></math>
            <div id="pane" class="stv-ui shelf-sec" data-original="injected" data-chapter="999" style="position:fixed" onclick="evil()">你好</div>
            <img src="https://tracker.test/pixel" srcset="https://tracker.test/pixel 2x">
            <img src="data:image/svg+xml,evil"/><a href="java&#10;script:evil()">bad</a>
            </body>""")
        assertTrue(result.select("script,iframe,form,input,button,svg,animate,math,style").isEmpty())
        assertTrue(result.select("[onclick],[onerror],[onload],[style],[srcset],[data-original],[data-chapter],[class]").isEmpty())
        assertNull(result.getElementById("pane"))
        assertEquals("你好", result.getElementById("epub-pane")?.text())
        assertTrue(result.text().contains("Readable text"))
        assertFalse(result.html().contains("tracker.test"))
        assertFalse(result.html().contains("evil()"))
    }

    @Test fun bookFormattingImagesAndCrossChapterFragmentsStillWork() {
        val result = clean("""<h1 id="top">标题</h1><p><em>Text</em> <ruby>字<rt>zi</rt></ruby></p>
            <svg><image xlink:href="../images/cover.jpg"/></svg>
            <img src="../images/page one.jpg">
            <table><tr><td colspan="2">table</td></tr></table>
            <a href="ch2.xhtml#next%20part">Next</a><a name="old-anchor"></a>
            <a href="https://example.test/novel">Source</a>""")
        assertEquals("标题", result.getElementById("epub-top")?.text())
        assertEquals("Text", result.selectFirst("em")?.text())
        assertEquals("https://shelf.localhost/res/images/cover.jpg", result.selectFirst("img")?.attr("src"))
        assertEquals("https://shelf.localhost/res/images/page one.jpg", result.select("img")[1].attr("src"))
        assertEquals("2", result.selectFirst("td")?.attr("colspan"))
        assertEquals("1", result.select("a")[0].attr("data-chapter"))
        assertEquals("next part", result.select("a")[0].attr("data-fragment"))
        assertNotNull(result.getElementById("epub-old-anchor"))
        assertEquals("noopener noreferrer", result.select("a").last()?.attr("rel"))
    }
}
