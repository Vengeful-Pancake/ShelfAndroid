package com.shelf.reader.data

import org.json.JSONArray

/** Provider document IDs are opaque. Folder names come from the actual SAF walk. */
object BookFolders {
    fun encode(path: List<String>): String = JSONArray(path).toString()
    fun decode(value: String?): List<String>? = value?.let {
        runCatching {
            val array = JSONArray(it)
            List(array.length()) { index -> array.getString(index) }
        }.getOrNull()
    }
}
