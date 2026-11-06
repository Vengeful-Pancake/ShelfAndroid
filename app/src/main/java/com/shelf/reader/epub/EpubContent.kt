package com.shelf.reader.epub

import org.jsoup.nodes.Document
import java.net.URI

/** Books supply presentation and text, never executable markup or reader UI attributes. */
internal object EpubContent {
    private val tags = setOf("a", "abbr", "b", "bdi", "bdo", "blockquote", "br", "caption", "cite", "code",
        "col", "colgroup", "dd", "del", "div", "dl", "dt", "em", "figcaption", "figure", "h1", "h2", "h3",
        "h4", "h5", "h6", "hr", "i", "img", "ins", "li", "mark", "ol", "p", "pre", "q", "rp", "rt",
        "ruby", "s", "samp", "section", "small", "span", "strong", "sub", "sup", "table", "tbody", "td",
        "tfoot", "th", "thead", "tr", "u", "ul", "var", "wbr")

    fun sanitize(document: Document, path: String, resourceUrl: (String) -> String, spineIndex: (String) -> Int): String {
        val body = document.body()
        body.select("script,style,link,meta,base,iframe,frame,frameset,object,embed,template,noscript").remove()
        // Calibre cover pages often put their raster cover inside an SVG wrapper.
        body.select("image").forEach { image ->
            image.tagName("img").attr("src", image.attr("href").ifEmpty { image.attr("xlink:href") })
        }
        for (element in body.getAllElements().toList()) {
            if (element === body) continue
            val tag = element.normalName()
            if (tag !in tags) { element.unwrap(); continue }
            val original = element.attributes().associate { it.key.lowercase() to it.value }
            element.clearAttributes()
            // A book cannot shadow pane/stv/Shelf bridge names or attach trusted data-* attributes.
            val id = original["id"].orEmpty().ifEmpty { if (tag == "a") original["name"].orEmpty() else "" }
            if (id.isNotBlank()) element.attr("id", "epub-$id")
            original["title"]?.let { element.attr("title", it) }
            original["lang"]?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,35}")) }?.let { element.attr("lang", it) }
            original["dir"]?.takeIf { it in setOf("ltr", "rtl", "auto") }?.let { element.attr("dir", it) }
            for (attribute in listOf("colspan", "rowspan", "start", "value", "span")) {
                original[attribute]?.toIntOrNull()?.takeIf { it in -1..1000 }?.let { element.attr(attribute, it.toString()) }
            }
            if (tag == "img") {
                original["alt"]?.let { element.attr("alt", it) }
                val source = original["src"].orEmpty().trim()
                // No remote tracking pixels, data: documents, srcset or executable URI schemes.
                if (source.isNotEmpty() && !source.startsWith("//") && runCatching { URI(source.replace(" ", "%20")).scheme == null }.getOrDefault(false)) {
                    element.attr("src", resourceUrl(EpubPaths.resolve(EpubPaths.dirName(path), source)))
                }
                element.attr("loading", "lazy")
            }
            if (tag == "a") {
                val href = original["href"].orEmpty().trim()
                val uri = runCatching { URI(href.replace(" ", "%20")) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                if (scheme in setOf("https", "http") && !uri?.host.isNullOrBlank() && uri?.userInfo == null) {
                    element.attr("href", href).attr("target", "_blank").attr("rel", "noopener noreferrer")
                } else if (scheme == null && uri != null && !href.startsWith("//")) {
                    val target = href.substringBefore('#')
                    val resolved = if (target.isNotEmpty()) EpubPaths.resolve(EpubPaths.dirName(path), target) else path
                    val index = spineIndex(resolved)
                    element.attr("href", "#")
                    if (index >= 0) {
                        element.attr("data-chapter", index.toString())
                        uri.rawFragment?.let { element.attr("data-fragment", EpubPaths.percentDecode(it)) }
                    }
                }
            }
        }
        return body.html()
    }
}
