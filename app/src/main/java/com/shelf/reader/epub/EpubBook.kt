package com.shelf.reader.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.w3c.dom.Node
import java.io.Closeable
import java.net.URI
import java.text.Normalizer
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element as DomElement

/**
 * Opens an .epub (a zip) and exposes metadata, cover, spine, table of contents and
 * per-chapter HTML with internal links and images rewritten so the reader WebView
 * can resolve them.
 */
class EpubBook(private val zip: ZipReader) : Closeable {

    companion object {
        private const val CONTAINER = "META-INF/container.xml"
        private const val NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
        private const val NS_OPF = "http://www.idpf.org/2007/opf"
        private const val NS_DC = "http://purl.org/dc/elements/1.1/"
        private const val NS_NCX = "http://www.daisy.org/z3986/2005/ncx/"

        private val CONTENT_NAME = Regex(
            "^(chapter|chap|ch|part|volume|book|chuong|hoi|quyen)( |$|\\d)|" +
                "^(prologue|epilogue|introduction|preface|foreword|afterword)( |$)"
        )
        private val AUXILIARY_FILE = Regex(
            "^(cover|coverpage|title|titlepage|halftitle|toc|contents|tableofcontents|" +
                "description|bookdescription|summary|synopsis|blurb|copyright|colophon|imprint|" +
                "bookinfo|metadata|aboutthisbook)(page)?\\d*$"
        )

        private val STRIP_TAGS = listOf("script", "link", "meta", "base", "iframe", "object", "embed", "style")

        private val MIME_BY_EXTENSION = mapOf(
            "html" to "text/html", "xhtml" to "application/xhtml+xml", "htm" to "text/html",
            "css" to "text/css", "js" to "text/javascript", "json" to "application/json",
            "xml" to "application/xml", "ncx" to "application/x-dtbncx+xml",
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
            "bmp" to "image/bmp", "ico" to "image/x-icon",
            "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff", "woff2" to "font/woff2",
            "mp3" to "audio/mpeg", "mp4" to "video/mp4", "m4a" to "audio/mp4",
        )

        fun guessMime(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return MIME_BY_EXTENSION[ext] ?: "application/octet-stream"
        }
    }

    val opfPath: String
    private val baseDir: String
    val meta: EpubMeta
    val manifest: Map<String, ManifestItem>
    /** Reading documents only. Package front matter is listed in [auxiliarySections]. */
    val spine: List<String>
    val sections: List<EpubSection>
    val auxiliarySections: List<EpubSection>
    val coverPath: String
    val toc: List<TocEntry>

    private val packageSpine: List<String>
    private val spineTitles: Array<String>

    init {
        opfPath = findOpf()
        baseDir = EpubPaths.dirName(opfPath)

        val opf = parseXml(zip.read(opfPath), "package document")
        manifest = readManifest(opf)
        val packageMeta = readMeta(opf)
        packageSpine = readSpine(opf)
        val navigation = readNavigation(opf)
        val classified = packageSpine.map { path ->
            classifySection(
                path = path,
                navigationLabel = navigation.firstOrNull { samePath(it.href, path) }?.label.orEmpty(),
                metadata = packageMeta,
            )
        }
        val readingSections = classified.filter(EpubSection::countsTowardProgress)
        // A malformed or highly unusual book is still more useful readable than empty.
        sections = if (readingSections.isNotEmpty()) readingSections else classified
        auxiliarySections = if (readingSections.isNotEmpty()) {
            classified.filterNot(EpubSection::countsTowardProgress)
        } else {
            emptyList()
        }
        spine = sections.map(EpubSection::path)
        meta = packageMeta.copy(chapters = spine.size)
        coverPath = findCover(opf)
        spineTitles = Array(spine.size) { "" }
        toc = readToc(navigation)
    }

    val chapterCount: Int get() = spine.size

    override fun close() = zip.close()

    // --------------------------------------------------------------- xml plumbing

    private fun parseXml(bytes: ByteArray, what: String): DomElement {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val doc = factory.newDocumentBuilder().parse(bytes.inputStream())
            doc.documentElement ?: throw EpubException("Empty $what")
        } catch (e: Exception) {
            throw EpubException("Could not read the $what: ${e.message}", e)
        }
    }

    private fun DomElement.childrenNamed(ns: String, name: String): List<DomElement> {
        val out = ArrayList<DomElement>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as DomElement
            if (el.localName == name && (el.namespaceURI == ns || el.namespaceURI == null)) out.add(el)
        }
        return out
    }

    private fun DomElement.firstNamed(ns: String, name: String): DomElement? =
        childrenNamed(ns, name).firstOrNull()

    private fun DomElement.descendantsNamed(ns: String, name: String): List<DomElement> {
        val out = ArrayList<DomElement>()
        val list = getElementsByTagNameNS(ns, name)
        for (i in 0 until list.length) out.add(list.item(i) as DomElement)
        if (out.isEmpty()) {
            val fallback = getElementsByTagName(name)
            for (i in 0 until fallback.length) out.add(fallback.item(i) as DomElement)
        }
        return out
    }

    private val DomElement.trimmedText: String get() = textContent?.trim().orEmpty()

    // ------------------------------------------------------------------- opf

    private fun findOpf(): String {
        runCatching {
            val root = parseXml(zip.read(CONTAINER), "container")
            val rootFiles = root.descendantsNamed(NS_CONTAINER, "rootfile")
            val fullPath = rootFiles.firstNotNullOfOrNull { it.getAttribute("full-path").takeIf(String::isNotBlank) }
            if (fullPath != null) return fullPath.trimStart('/')
        }
        zip.names.firstOrNull { it.lowercase().endsWith(".opf") }?.let { return it }
        throw EpubException("No OPF package document found")
    }

    private fun readMeta(root: DomElement): EpubMeta {
        val md = root.firstNamed(NS_OPF, "metadata")
            ?: root.descendantsNamed(NS_OPF, "metadata").firstOrNull()
            ?: return EpubMeta()

        fun dc(tag: String): String =
            md.childrenNamed(NS_DC, tag).firstOrNull()?.trimmedText.orEmpty()

        val creators = md.childrenNamed(NS_DC, "creator")
            .map { it.trimmedText }
            .filter { it.isNotEmpty() }
            .distinct()

        var series = ""
        for (el in md.childrenNamed(NS_OPF, "meta")) {
            val name = el.getAttribute("name").ifEmpty { el.getAttribute("property") }
            if (name == "calibre:series" || name == "belongs-to-collection") {
                series = el.getAttribute("content").ifEmpty { el.trimmedText }.trim()
            }
        }

        return EpubMeta(
            title = cleanMetadataTitle(dc("title")).ifEmpty { "Untitled" },
            author = creators.joinToString(", "),
            language = dc("language"),
            publisher = dc("publisher"),
            description = cleanMetadataDescription(dc("description")),
            series = series,
            identifier = dc("identifier"),
            subjects = md.childrenNamed(NS_DC, "subject").map { it.trimmedText }.filter { it.isNotEmpty() },
        )
    }

    private fun readManifest(root: DomElement): Map<String, ManifestItem> {
        val out = LinkedHashMap<String, ManifestItem>()
        val mf = root.firstNamed(NS_OPF, "manifest")
            ?: root.descendantsNamed(NS_OPF, "manifest").firstOrNull()
            ?: return out
        for (item in mf.childrenNamed(NS_OPF, "item")) {
            val id = item.getAttribute("id")
            if (id.isNullOrBlank()) continue
            out[id] = ManifestItem(
                href = EpubPaths.resolve(baseDir, item.getAttribute("href")),
                mediaType = item.getAttribute("media-type").orEmpty(),
                properties = item.getAttribute("properties").orEmpty(),
            )
        }
        return out
    }

    private fun readSpine(root: DomElement): List<String> {
        val paths = ArrayList<String>()
        val sp = root.firstNamed(NS_OPF, "spine") ?: root.descendantsNamed(NS_OPF, "spine").firstOrNull()
        if (sp != null) {
            for (ref in sp.childrenNamed(NS_OPF, "itemref")) {
                if (ref.getAttribute("linear").orEmpty().ifEmpty { "yes" }.lowercase() == "no") continue
                val item = manifest[ref.getAttribute("idref")]
                if (item != null && item.href.isNotEmpty()) paths.add(item.href)
            }
        }
        if (paths.isEmpty()) {
            // Damaged spine: fall back to every html document in the manifest.
            manifest.values.filter { it.mediaType.contains("html") }.forEach { paths.add(it.href) }
        }
        return paths
    }

    private fun classifySection(
        path: String,
        navigationLabel: String,
        metadata: EpubMeta,
    ): EpubSection {
        val item = manifest.values.firstOrNull { samePath(it.href, path) }
        val properties = item?.properties.orEmpty()
            .split(Regex("\\s+"))
            .map(String::lowercase)
        if ("nav" in properties) return EpubSection(path, EpubSectionKind.CONTENTS, navigationLabel)

        val soup = runCatching { Jsoup.parse(zip.readText(path), "") }.getOrNull()
            ?: return EpubSection(path, EpubSectionKind.CONTENT, navigationLabel)
        val heading = soup.selectFirst("h1, h2, h3")?.text().orEmpty()
        val firstBlock = soup.body()?.selectFirst("h1, h2, h3, h4, h5, h6, p")?.text().orEmpty()
        val documentTitle = soup.selectFirst("title")?.text().orEmpty()
        val names = listOf(navigationLabel, heading, firstBlock, documentTitle)
            .map(::semanticKey)
            .filter(String::isNotEmpty)

        // A clear chapter or part label wins over generic filename heuristics.
        if (names.any(CONTENT_NAME::containsMatchIn)) {
            return EpubSection(path, EpubSectionKind.CONTENT, navigationLabel)
        }

        val semanticTokens = soup.allElements.flatMap { element ->
            listOf(element.attr("epub:type"), element.attr("type"), element.attr("role"))
                .flatMap { it.split(Regex("\\s+")) }
                .map(::semanticKey)
        }.filter(String::isNotEmpty).toSet()
        semanticKind(semanticTokens)?.let { return EpubSection(path, it, navigationLabel) }

        names.firstNotNullOfOrNull(::namedAuxiliaryKind)?.let {
            return EpubSection(path, it, navigationLabel)
        }

        val fileStem = path.substringAfterLast('/').substringBeforeLast('.')
        val compactFile = semanticKey(fileStem).replace(" ", "")
        if (AUXILIARY_FILE.matches(compactFile)) {
            return EpubSection(
                path,
                namedAuxiliaryKind(semanticKey(fileStem)) ?: EpubSectionKind.OTHER_AUXILIARY,
                navigationLabel,
            )
        }

        val text = soup.body()?.text().orEmpty().replace(Regex("\\s+"), " ").trim()
        val textKey = semanticKey(text)
        val titleKey = semanticKey(metadata.title)
        val authorKey = semanticKey(metadata.author)
        val descriptionKey = semanticKey(metadata.description)

        // Exporters often give a title page an opaque filename and no epub:type.
        val looksLikeTitlePage = text.length <= 500 && titleKey.length >= 3 &&
            textKey.contains(titleKey) &&
            (heading.isNotBlank() || documentTitle.isNotBlank()) &&
            (authorKey.isEmpty() || textKey.contains(authorKey) || textKey.length <= titleKey.length + 30)
        if (looksLikeTitlePage) return EpubSection(path, EpubSectionKind.TITLE_PAGE, navigationLabel)

        // Match an unlabelled description page against dc:description when present.
        val descriptionSample = descriptionKey.take(140)
        if (descriptionSample.length >= 24 && textKey.contains(descriptionSample)) {
            return EpubSection(path, EpubSectionKind.DESCRIPTION, navigationLabel)
        }

        // A document made primarily of internal links is a contents page.
        val links = soup.select("a[href]").filter { !hasScheme(it.attr("href")) }
        val linkTextLength = links.sumOf { it.text().length }
        if (links.size >= 2 && text.isNotEmpty() && linkTextLength * 2 >= text.length) {
            return EpubSection(path, EpubSectionKind.CONTENTS, navigationLabel)
        }

        return EpubSection(path, EpubSectionKind.CONTENT, navigationLabel)
    }

    private fun semanticKind(tokens: Set<String>): EpubSectionKind? = when {
        tokens.any { it == "cover" || it == "frontcover" } -> EpubSectionKind.COVER
        tokens.any { it == "titlepage" || it == "halftitlepage" || it == "fulltitlepage" } ->
            EpubSectionKind.TITLE_PAGE
        tokens.any { it == "toc" || it == "table of contents" } -> EpubSectionKind.CONTENTS
        tokens.any { it == "copyright page" || it == "copyright" } -> EpubSectionKind.COPYRIGHT
        tokens.any { it in setOf("colophon", "imprint", "dedication", "acknowledgments", "other credits") } ->
            EpubSectionKind.OTHER_AUXILIARY
        else -> null
    }

    private fun namedAuxiliaryKind(key: String): EpubSectionKind? = when (key) {
        "cover", "cover page", "front cover" -> EpubSectionKind.COVER
        "title", "title page", "half title", "half title page", "full title page" ->
            EpubSectionKind.TITLE_PAGE
        "description", "book description", "summary", "synopsis", "blurb", "about this book",
        "gioi thieu", "tom tat", "van an" -> EpubSectionKind.DESCRIPTION
        "toc", "contents", "table of contents", "navigation", "muc luc" -> EpubSectionKind.CONTENTS
        "copyright", "copyright page" -> EpubSectionKind.COPYRIGHT
        "colophon", "imprint", "book information", "metadata", "dedication", "acknowledgments" ->
            EpubSectionKind.OTHER_AUXILIARY
        else -> null
    }

    private fun semanticKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun cleanMetadataTitle(value: String): String {
        var title = cleanMetadataText(value)
        val pairs = listOf('(' to ')', '[' to ']', '{' to '}')
        for ((open, close) in pairs) {
            if (title.startsWith(open) && close !in title) title = title.drop(1).trimStart()
            if (title.endsWith(close) && open !in title) title = title.dropLast(1).trimEnd()
        }
        return title
    }

    private fun cleanMetadataDescription(value: String): String {
        val description = cleanMetadataText(value)
        val key = semanticKey(description)
        return if (
            key.matches(Regex("^(generated|created|converted|exported) by [a-z0-9 ]+$")) ||
            key in setOf("no description", "n a", "none")
        ) "" else description
    }

    private fun cleanMetadataText(value: String): String = Jsoup.parseBodyFragment(value)
        .text()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun samePath(first: String, second: String): Boolean = first.equals(second, ignoreCase = true)

    private fun findCover(root: DomElement): String {
        manifest.values.firstOrNull { it.properties.contains("cover-image") }?.let { return it.href }

        val md = root.firstNamed(NS_OPF, "metadata")
        if (md != null) {
            for (el in md.childrenNamed(NS_OPF, "meta")) {
                if (el.getAttribute("name").orEmpty().lowercase() == "cover") {
                    val item = manifest[el.getAttribute("content")]
                    if (item != null && item.mediaType.startsWith("image/")) return item.href
                }
            }
        }
        manifest.values.firstOrNull {
            it.mediaType.startsWith("image/") && it.href.lowercase().contains("cover")
        }?.let { return it.href }
        return ""
    }

    private fun spineIndexOf(path: String): Int {
        val exact = spine.indexOf(path)
        if (exact >= 0) return exact
        val low = path.lowercase()
        return spine.indexOfFirst { it.lowercase() == low }
    }

    // ------------------------------------------------------------------- toc

    private fun readNavigation(root: DomElement): List<TocEntry> {
        val nav = tocFromNav()
        return if (nav.isNotEmpty()) nav else tocFromNcx(root)
    }

    private fun readToc(navigation: List<TocEntry>): List<TocEntry> {
        var entries = navigation
        if (entries.isEmpty()) {
            entries = spine.mapIndexed { i, p -> TocEntry("Section ${i + 1}", p, spineIndex = i) }
        }
        return entries
            .map { if (it.spineIndex < 0) it.copy(spineIndex = spineIndexOf(it.href)) else it }
            .filter { it.spineIndex >= 0 }
    }

    private fun tocFromNav(): List<TocEntry> {
        val navPath = manifest.values
            .firstOrNull { it.properties.split(Regex("\\s+")).contains("nav") }
            ?.href ?: return emptyList()
        if (!zip.has(navPath)) return emptyList()

        val soup = Jsoup.parse(zip.readText(navPath), "")
        val nav = soup.select("nav").firstOrNull { el ->
            el.attr("epub:type") == "toc" || el.attr("type") == "toc"
        } ?: soup.selectFirst("nav") ?: return emptyList()

        val base = EpubPaths.dirName(navPath)
        val out = ArrayList<TocEntry>()

        fun walk(ol: Element, depth: Int) {
            for (li in ol.children().filter { it.tagName() == "li" }) {
                val anchor = li.children().firstOrNull { it.tagName() == "a" || it.tagName() == "span" }
                if (anchor != null) {
                    val href = if (anchor.tagName() == "a") anchor.attr("href") else ""
                    val target = href.substringBefore('#')
                    val fragment = if (href.contains('#')) href.substringAfter('#') else ""
                    out.add(
                        TocEntry(
                            label = anchor.text().trim().ifEmpty { "Untitled" },
                            href = if (target.isNotEmpty()) EpubPaths.resolve(base, target) else "",
                            fragment = fragment,
                            depth = depth,
                        )
                    )
                }
                li.children().firstOrNull { it.tagName() == "ol" }?.let { walk(it, depth + 1) }
            }
        }

        nav.selectFirst("ol")?.let { walk(it, 0) }
        return out
    }

    private fun tocFromNcx(root: DomElement): List<TocEntry> {
        var ncxPath = ""
        val sp = root.firstNamed(NS_OPF, "spine")
        val tocId = sp?.getAttribute("toc")
        if (!tocId.isNullOrBlank()) manifest[tocId]?.let { ncxPath = it.href }
        if (ncxPath.isEmpty()) {
            manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.let { ncxPath = it.href }
        }
        if (ncxPath.isEmpty() || !zip.has(ncxPath)) return emptyList()

        val ncx = runCatching { parseXml(zip.read(ncxPath), "contents") }.getOrNull() ?: return emptyList()
        val base = EpubPaths.dirName(ncxPath)
        val out = ArrayList<TocEntry>()

        fun walk(parent: DomElement, depth: Int) {
            for (point in parent.childrenNamed(NS_NCX, "navPoint")) {
                val label = point.firstNamed(NS_NCX, "navLabel")
                    ?.firstNamed(NS_NCX, "text")?.trimmedText.orEmpty()
                val src = point.firstNamed(NS_NCX, "content")?.getAttribute("src").orEmpty()
                val target = src.substringBefore('#')
                val fragment = if (src.contains('#')) src.substringAfter('#') else ""
                out.add(
                    TocEntry(
                        label = label.ifEmpty { "Untitled" },
                        href = if (target.isNotEmpty()) EpubPaths.resolve(base, target) else "",
                        fragment = fragment,
                        depth = depth,
                    )
                )
                walk(point, depth + 1)
            }
        }

        ncx.firstNamed(NS_NCX, "navMap")?.let { walk(it, 0) }
        return out
    }

    // -------------------------------------------------------------- resources

    /** Raw bytes plus a mime type for a file inside the archive. */
    fun resource(path: String): Pair<ByteArray, String> {
        val data = try {
            zip.read(path)
        } catch (e: Exception) {
            throw EpubException("Missing resource $path", e)
        }
        val declared = manifest.values.firstOrNull { it.href == path }?.mediaType
        return data to (declared?.takeIf { it.isNotBlank() } ?: guessMime(path))
    }

    fun cover(): Pair<ByteArray, String>? {
        if (coverPath.isEmpty() || !zip.has(coverPath)) return null
        return runCatching { resource(coverPath) }.getOrNull()
    }

    // --------------------------------------------------------------- chapters

    /**
     * Sanitised chapter HTML. [resourceUrl] turns an archive-internal path into
     * something the WebView can fetch.
     */
    fun chapter(index: Int, resourceUrl: (String) -> String): Chapter {
        if (index !in spine.indices) throw EpubException("Chapter $index is out of range")
        val path = spine[index]
        val base = EpubPaths.dirName(path)
        val soup: Document = Jsoup.parse(zip.readText(path), "")

        val docTitle = soup.selectFirst("title")?.text()?.trim().orEmpty()

        // Drop anything executable or style-bearing — reader settings always win.
        soup.select(STRIP_TAGS.joinToString(",")).remove()
        soup.select("[style]").forEach { el ->
            // Keep alignment-ish inline styles out; publisher CSS is dropped on purpose.
            el.removeAttr("style")
        }
        soup.allElements.forEach { el ->
            el.attributes().map { it.key }
                .filter { it.startsWith("on", ignoreCase = true) }
                .forEach { el.removeAttr(it) }
        }

        for (img in soup.select("img, image")) {
            for (attr in listOf("src", "xlink:href", "href")) {
                val value = img.attr(attr)
                if (value.isNotEmpty() && !hasScheme(value)) {
                    img.attr(attr, resourceUrl(EpubPaths.resolve(base, value)))
                }
            }
            img.removeAttr("width")
            img.removeAttr("height")
            img.attr("loading", "lazy")
        }

        for (a in soup.select("a")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val scheme = schemeOf(href)
            if (scheme == "http" || scheme == "https" || scheme == "mailto") {
                a.attr("target", "_blank")
                a.attr("rel", "noopener noreferrer")
                continue
            }
            val target = href.substringBefore('#')
            val fragment = if (href.contains('#')) href.substringAfter('#') else ""
            val resolved = if (target.isNotEmpty()) EpubPaths.resolve(base, target) else path
            val idx = spineIndexOf(resolved)
            a.attr("href", "#")
            if (idx >= 0) {
                a.attr("data-chapter", idx.toString())
                if (fragment.isNotEmpty()) a.attr("data-fragment", fragment)
            }
        }

        val body = soup.body()
        val html = body?.html() ?: soup.html()

        synchronized(spineTitles) {
            if (spineTitles[index].isEmpty()) {
                val heading = soup.selectFirst("h1, h2, h3")?.text()?.trim()
                spineTitles[index] = heading?.takeIf { it.isNotEmpty() } ?: docTitle
            }
        }

        val plain = soup.text()
        return Chapter(
            index = index,
            path = path,
            title = spineTitles[index].ifEmpty { docTitle }.ifEmpty { "Section ${index + 1}" },
            html = html,
            words = Regex("\\w+").findAll(plain).count(),
        )
    }

    fun tocLabelFor(index: Int): String =
        toc.firstOrNull { it.spineIndex == index }?.label.orEmpty()

    /**
     * Converts an index saved by versions that counted the complete package spine
     * into the filtered reading-spine index used now.
     */
    fun readingIndexForPackageIndex(index: Int): Int {
        if (spine.isEmpty() || packageSpine.isEmpty()) return 0
        val oldIndex = index.coerceIn(0, packageSpine.lastIndex)
        val oldPath = packageSpine[oldIndex]
        val exact = spine.indexOfFirst { samePath(it, oldPath) }
        if (exact >= 0) return exact

        val next = (oldIndex + 1 until packageSpine.size).firstNotNullOfOrNull { packageIndex ->
            val candidate = packageSpine[packageIndex]
            spine.indexOfFirst { samePath(it, candidate) }.takeIf { it >= 0 }
        }
        return next ?: spine.lastIndex
    }

    private fun hasScheme(value: String): Boolean = schemeOf(value).isNotEmpty()

    private fun schemeOf(value: String): String = runCatching {
        URI(value).scheme.orEmpty()
    }.getOrElse {
        val i = value.indexOf(':')
        if (i > 0 && value.take(i).all { it.isLetterOrDigit() || it in "+-." }) value.take(i) else ""
    }
}
