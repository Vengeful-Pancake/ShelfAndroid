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

    data class Edition(val key: String, val tags: List<String>)

    fun read(input: InputStream): Map<String, Edition> {
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
                val bookId = item.optString("id").trim()
                val tags = item.optJSONObject("tag_classification")?.optJSONArray("tags")
                val names = tags?.let { array -> cleanBookTags(List(array.length()) { array.optJSONObject(it)?.optString("name").orEmpty() }) }.orEmpty()
                val files = item.optJSONObject("files") ?: continue
                for (role in listOf("vi_epub", "zh_epub")) {
                    val editions = files.optJSONArray(role) ?: continue
                    for (j in 0 until editions.length()) {
                        val path = pathKey(editions.getString(j))
                        val key = if (bookId.isEmpty()) "" else "$role:$bookId"
                        val previous = get(path)
                        val unambiguous = if (previous == null || previous.key == key) key else ""
                        put(path, Edition(unambiguous, cleanBookTags(previous?.tags.orEmpty() + names)))
                    }
                }
            }
        }
    }
}
