package com.shelf.reader.epub

/** Thrown for anything that stops a book being read. Never fatal to a scan. */
class EpubException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class TocEntry(
    val label: String,
    val href: String = "",
    val fragment: String = "",
    val spineIndex: Int = -1,
    val depth: Int = 0,
)

data class EpubMeta(
    val title: String = "Untitled",
    val author: String = "",
    val language: String = "",
    val publisher: String = "",
    val description: String = "",
    val series: String = "",
    val identifier: String = "",
    val subjects: List<String> = emptyList(),
    val chapters: Int = 0,
)

data class Chapter(
    val index: Int,
    val path: String,
    val title: String,
    val html: String,
    val words: Int,
)

/** The semantic role of an XHTML document in the EPUB package. */
enum class EpubSectionKind {
    CONTENT,
    COVER,
    TITLE_PAGE,
    DESCRIPTION,
    CONTENTS,
    COPYRIGHT,
    OTHER_AUXILIARY,
}

data class EpubSection(
    val path: String,
    val kind: EpubSectionKind,
    val label: String = "",
) {
    val countsTowardProgress: Boolean get() = kind == EpubSectionKind.CONTENT
}

data class ManifestItem(
    val href: String,
    val mediaType: String,
    val properties: String,
)

internal object EpubPaths {

    /** Percent-decode without turning '+' into a space — these are paths, not query strings. */
    fun percentDecode(input: String): String {
        if (!input.contains('%')) return input
        val out = java.io.ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    out.write(value)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Collapse `.` and `..` segments, drop leading slashes. */
    fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (segment in path.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." ->
                    if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.size - 1)
                    else parts.add("..")
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    /** Resolve an href relative to a directory inside the archive. */
    fun resolve(baseDir: String, href: String?): String {
        val decoded = percentDecode(href.orEmpty()).replace('\\', '/')
        if (decoded.isEmpty()) return ""
        val joined = when {
            decoded.startsWith("/") -> decoded
            baseDir.isNotEmpty() -> "$baseDir/$decoded"
            else -> decoded
        }
        return normalize(joined)
    }

    fun dirName(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i <= 0) "" else path.substring(0, i)
    }
}
