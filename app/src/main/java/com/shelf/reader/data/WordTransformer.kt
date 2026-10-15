package com.shelf.reader.data

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Handles text transformations based on user-defined replacement rules.
 * Rules can come from an internal settings string or an external .txt file.
 */
class WordTransformer(
    private val context: Context,
    private val internalRules: String,
    private val externalUri: String? = null
) {
    private val rules: List<Pair<String, String>> by lazy {
        val list = mutableListOf<Pair<String, String>>()

        // Load internal rules first
        parseRules(internalRules, list)

        // Augment with external rules if available
        externalUri?.takeIf { it.isNotBlank() }?.let { uriString ->
            runCatching {
                val uri = Uri.parse(uriString)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            parseLine(line, list)
                        }
                    }
                }
            }
        }
        list
    }

    fun transform(text: String): String {
        if (rules.isEmpty()) return text
        // Use Jsoup to only replace text content, avoiding HTML tags and resource URLs.
        val doc = Jsoup.parseBodyFragment(text)
        doc.body().traverse { node, _ ->
            if (node is TextNode) {
                var content = node.text()
                for ((old, new) in rules) {
                    content = content.replace(old, new, ignoreCase = true)
                }
                node.text(content)
            }
        }
        return doc.body().html()
    }

    fun transformPlain(text: String): String {
        if (rules.isEmpty()) return text
        var result = text
        for ((old, new) in rules) {
            result = result.replace(old, new, ignoreCase = true)
        }
        return result
    }

    companion object {
        private fun parseRules(input: String, out: MutableList<Pair<String, String>>) {
            input.lineSequence().forEach { parseLine(it, out) }
        }

        private fun parseLine(line: String, out: MutableList<Pair<String, String>>) {
            val parts = line.split('=', limit = 2)
            if (parts.size == 2) {
                val old = parts[0].trim()
                val new = parts[1].trim()
                if (old.isNotEmpty()) {
                    out.add(old to new)
                }
            }
        }
    }
}
