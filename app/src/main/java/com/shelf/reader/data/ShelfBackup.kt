package com.shelf.reader.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupSummary(
    val booksMatched: Int,
    val progressItems: Int,
    val bookmarks: Int,
    val annotations: Int,
)

object ShelfBackup {
    private const val VERSION = 1

    suspend fun export(
        context: Context,
        dao: ShelfDao,
        settingsStore: SettingsStore,
        uri: Uri,
    ): BackupSummary = withContext(Dispatchers.IO) {
        val books = dao.allBooks()
        val progress = dao.allProgress()
        val bookmarks = dao.allBookmarks()
        val annotations = dao.allAnnotations()
        val favorites = dao.allFavorites()
        val libraries = dao.allCustomLibraries()
        val joins = dao.allLibraryJoins()
        val sessions = dao.allReadingSessions()

        val root = JSONObject()
            .put("format", "shelf-portable-backup")
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("settings", settingsToJson(settingsStore.current()))
            .put("books", JSONArray(books.map(::bookToJson)))
            .put("progress", JSONArray(progress.map(::progressToJson)))
            .put("bookmarks", JSONArray(bookmarks.map(::bookmarkToJson)))
            .put("annotations", JSONArray(annotations.map(::annotationToJson)))
            .put("favorites", JSONArray(favorites.map { JSONObject().put("bookId", it.bookId) }))
            .put("libraries", JSONArray(libraries.map {
                JSONObject().put("id", it.id).put("name", it.name).put("addedAt", it.addedAt)
            }))
            .put("libraryBooks", JSONArray(joins.map {
                JSONObject().put("libraryId", it.libraryId).put("bookId", it.bookId)
            }))
            .put("readingSessions", JSONArray(sessions.map {
                JSONObject()
                    .put("bookId", it.bookId)
                    .put("startedAt", it.startedAt)
                    .put("durationSeconds", it.durationSeconds)
            }))

        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
            it.write(root.toString(2))
        } ?: error("Could not open the selected file")

        BackupSummary(books.size, progress.size, bookmarks.size, annotations.size)
    }

    suspend fun restore(
        context: Context,
        dao: ShelfDao,
        settingsStore: SettingsStore,
        uri: Uri,
    ): BackupSummary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val value = reader.readText()
            require(value.length <= 20_000_000) { "Backup is too large" }
            value
        } ?: error("Could not open the selected file")
        val root = JSONObject(text)
        require(root.optString("format") == "shelf-portable-backup") { "This is not a Shelf backup" }
        require(root.optInt("version") in 1..VERSION) { "This backup uses a newer format" }

        val currentBooks = dao.allBooks()
        val currentById = currentBooks.associateBy { it.id }
        val backupBooks = root.array("books").objects().associateBy { it.optString("id") }
        val idMap = LinkedHashMap<String, String>()
        backupBooks.forEach { (oldId, saved) ->
            val match = currentById[oldId]
                ?: currentBooks.firstOrNull {
                    it.filename.equals(saved.optString("filename"), true) &&
                        it.size == saved.optLong("size", -1)
                }
                ?: currentBooks.firstOrNull {
                    normalize(it.title) == normalize(saved.optString("title")) &&
                        normalize(it.author) == normalize(saved.optString("author"))
                }
            if (match != null) idMap[oldId] = match.id
        }

        val progress = root.array("progress").objects().mapNotNull { item ->
            idMap[item.optString("bookId")]?.let { newId ->
                ProgressEntity(
                    bookId = newId,
                    chapter = item.optInt("chapter"),
                    offset = item.optDouble("offset").toFloat().coerceIn(0f, 1f),
                    title = item.optString("title"),
                    at = item.optLong("at"),
                )
            }
        }
        val bookmarks = root.array("bookmarks").objects().mapNotNull { item ->
            idMap[item.optString("bookId")]?.let { newId ->
                BookmarkEntity(
                    bookId = newId,
                    chapter = item.optInt("chapter"),
                    offset = item.optDouble("offset").toFloat().coerceIn(0f, 1f),
                    title = item.optString("title"),
                    note = item.optString("note"),
                    at = item.optLong("at"),
                )
            }
        }
        val annotations = root.array("annotations").objects().mapNotNull { item ->
            idMap[item.optString("bookId")]?.let { newId ->
                AnnotationEntity(
                    bookId = newId,
                    chapter = item.optInt("chapter"),
                    offset = item.optDouble("offset").toFloat().coerceIn(0f, 1f),
                    chapterTitle = item.optString("chapterTitle"),
                    quote = item.optString("quote"),
                    note = item.optString("note"),
                    at = item.optLong("at"),
                )
            }
        }
        val favorites = root.array("favorites").objects().mapNotNull { item ->
            idMap[item.optString("bookId")]?.let(::FavoriteEntity)
        }
        val libraries = root.array("libraries").objects().map { item ->
            CustomLibraryEntity(
                id = item.optLong("id"),
                name = item.optString("name").ifBlank { "Imported library" },
                addedAt = item.optLong("addedAt", System.currentTimeMillis()),
            )
        }
        val validLibraryIds = libraries.mapTo(HashSet()) { it.id }
        val joins = root.array("libraryBooks").objects().mapNotNull { item ->
            val libraryId = item.optLong("libraryId")
            val bookId = idMap[item.optString("bookId")]
            if (libraryId in validLibraryIds && bookId != null) LibraryBookJoinEntity(libraryId, bookId) else null
        }
        val sessions = root.array("readingSessions").objects().mapNotNull { item ->
            idMap[item.optString("bookId")]?.let { newId ->
                ReadingSessionEntity(
                    bookId = newId,
                    startedAt = item.optLong("startedAt"),
                    durationSeconds = item.optLong("durationSeconds").coerceAtLeast(0),
                )
            }
        }

        dao.replacePortableData(progress, bookmarks, annotations, favorites, libraries, joins, sessions)
        root.optJSONObject("settings")?.let { settingsStore.replace(settingsFromJson(it)) }

        BackupSummary(idMap.size, progress.size, bookmarks.size, annotations.size)
    }

    private fun bookToJson(book: BookEntity) = JSONObject()
        .put("id", book.id)
        .put("filename", book.filename)
        .put("size", book.size)
        .put("title", book.title)
        .put("author", book.author)

    private fun progressToJson(item: ProgressEntity) = JSONObject()
        .put("bookId", item.bookId).put("chapter", item.chapter).put("offset", item.offset)
        .put("title", item.title).put("at", item.at)

    private fun bookmarkToJson(item: BookmarkEntity) = JSONObject()
        .put("bookId", item.bookId).put("chapter", item.chapter).put("offset", item.offset)
        .put("title", item.title).put("note", item.note).put("at", item.at)

    private fun annotationToJson(item: AnnotationEntity) = JSONObject()
        .put("bookId", item.bookId).put("chapter", item.chapter).put("offset", item.offset)
        .put("chapterTitle", item.chapterTitle).put("quote", item.quote).put("note", item.note)
        .put("at", item.at)

    private fun settingsToJson(value: ReaderSettings) = JSONObject()
        .put("background", value.background).put("foreground", value.foreground)
        .put("font", value.font.name).put("textSize", value.textSize)
        .put("lineHeight", value.lineHeight).put("margin", value.margin)
        .put("justify", value.justify).put("indent", value.indent)
        .put("keepScreenOn", value.keepScreenOn).put("tapToTurn", value.tapToTurn)
        .put("fullscreen", value.fullscreen).put("ttsSpeed", value.ttsSpeed)
        .put("ttsVoice", value.ttsVoice).put("titleMaxLines", value.titleMaxLines)
        .put("titleFontSize", value.titleFontSize).put("wordReplacements", value.wordReplacements)
        .put("wordReplacementsUri", value.wordReplacementsUri).put("twoColumns", value.twoColumns)
        .put("useFilenameAsTitle", value.useFilenameAsTitle).put("gridDensity", value.gridDensity)
        .put("sort", value.sort).put("filter", value.filter)
        .put("continuousScroll", value.continuousScroll).put("hideThumbnails", value.hideThumbnails)

    private fun settingsFromJson(json: JSONObject): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            background = json.optString("background", defaults.background),
            foreground = json.optString("foreground", defaults.foreground),
            font = ReaderFont.from(json.optString("font")),
            textSize = json.optInt("textSize", defaults.textSize),
            lineHeight = json.optDouble("lineHeight", defaults.lineHeight.toDouble()).toFloat(),
            margin = json.optInt("margin", defaults.margin),
            justify = json.optBoolean("justify", defaults.justify),
            indent = json.optBoolean("indent", defaults.indent),
            keepScreenOn = json.optBoolean("keepScreenOn", defaults.keepScreenOn),
            tapToTurn = json.optBoolean("tapToTurn", defaults.tapToTurn),
            fullscreen = json.optBoolean("fullscreen", defaults.fullscreen),
            ttsSpeed = json.optDouble("ttsSpeed", defaults.ttsSpeed.toDouble()).toFloat(),
            ttsVoice = json.optString("ttsVoice").takeIf { it.isNotBlank() && it != "null" },
            titleMaxLines = json.optInt("titleMaxLines", defaults.titleMaxLines),
            titleFontSize = json.optInt("titleFontSize", defaults.titleFontSize),
            wordReplacements = json.optString("wordReplacements", defaults.wordReplacements),
            wordReplacementsUri = json.optString("wordReplacementsUri")
                .takeIf { it.isNotBlank() && it != "null" },
            twoColumns = json.optBoolean("twoColumns", defaults.twoColumns),
            useFilenameAsTitle = json.optBoolean("useFilenameAsTitle", defaults.useFilenameAsTitle),
            gridDensity = json.optString("gridDensity", defaults.gridDensity),
            continuousScroll = json.optBoolean("continuousScroll", defaults.continuousScroll),
            hideThumbnails = json.optBoolean("hideThumbnails", defaults.hideThumbnails),
            sort = json.optString("sort", defaults.sort),
            filter = json.optString("filter", defaults.filter),
        )
    }

    private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: JSONArray()

    private fun JSONArray.objects(): List<JSONObject> = buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
