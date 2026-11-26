package com.shelf.reader.data

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.Normalizer

internal fun tagKey(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase()

internal fun cleanBookTags(tags: List<String>): List<String> = tags.map(String::trim)
    .filter(String::isNotEmpty).distinctBy(::tagKey)

class BookTagConverter {
    @TypeConverter
    fun encode(tags: List<String>): String = JSONArray(tags).toString()

    @TypeConverter
    fun decode(value: String): List<String> {
        val array = JSONArray(value)
        return List(array.length(), array::getString)
    }
}

internal object TagCatalog {
    const val FILE_NAME = "book-pairs.json"
    private const val MAX_BYTES = 32 * 1024 * 1024

    fun pathKey(path: String): String = Normalizer.normalize(path.replace('\\', '/'), Normalizer.Form.NFC).lowercase()

    fun read(input: InputStream): Map<String, List<String>> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= MAX_BYTES) { "$FILE_NAME exceeds 32 MB" }
            out.write(buffer, 0, count)
        }
        val catalog = JSONObject(out.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF"))
        require(catalog.optInt("schema_version") == 1) { "Unsupported $FILE_NAME format" }
        val books = catalog.getJSONArray("books")
        return buildMap {
            for (i in 0 until books.length()) {
                val item = books.getJSONObject(i)
                val tags = item.optJSONObject("tag_classification")?.optJSONArray("tags") ?: continue
                val names = cleanBookTags(List(tags.length()) { tags.optJSONObject(it)?.optString("name").orEmpty() })
                if (names.isEmpty()) continue
                val files = item.getJSONObject("files")
                for (role in listOf("vi_epub", "zh_epub")) {
                    val editions = files.optJSONArray(role) ?: continue
                    for (j in 0 until editions.length()) {
                        val path = pathKey(editions.getString(j))
                        put(path, cleanBookTags(get(path).orEmpty() + names))
                    }
                }
            }
        }
    }
}
