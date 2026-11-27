package com.shelf.reader.ui.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

internal object ReaderSearch {
    val blocks = "address article aside blockquote dd div dl dt figcaption figure footer h1 h2 h3 h4 h5 h6 header hr li main nav ol p pre section table td th tr ul".split(' ').toSet()
    private val whitespace = Regex("[\\s\\u00a0]+")

    fun normalize(text: String): String = text.replace(whitespace, " ").trim(' ')

    /** Matches the rendered chapter text, preserving spaces across block boundaries and inline spans. */
    fun text(html: String): String = buildString {
        val body = Jsoup.parseBodyFragment(html).body()
        body.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> append(node.wholeText)
                    is Element -> if (node.normalName() == "br" || node.normalName() in blocks) append(' ')
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in blocks) append(' ')
            }
        })
    }.let { normalize(it) }

    fun hits(text: String, needle: String): Sequence<Int> = sequence {
        if (needle.isEmpty()) return@sequence
        var start = text.indexOf(needle, ignoreCase = true)
        while (start >= 0) {
            yield(start)
            start = text.indexOf(needle, start + needle.length, ignoreCase = true)
        }
    }
}
