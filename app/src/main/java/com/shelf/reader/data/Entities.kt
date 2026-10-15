package com.shelf.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A folder the reader was pointed at, held by a persisted SAF tree permission. */
@Entity(tableName = "roots")
data class RootEntity(
    @PrimaryKey val treeUri: String,
    val name: String,
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "books",
    indices = [Index("treeUri"), Index("title"), Index("author")],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val docUri: String,
    val treeUri: String,
    val source: String,
    val filename: String,
    val folder: String,
    val stamp: String,
    val size: Long,
    val added: Long,
    val title: String,
    val author: String = "",
    val series: String = "",
    val language: String = "",
    val publisher: String = "",
    val description: String = "",
    val chapters: Int = 0,
    val coverFile: String? = null,
    val broken: Boolean = false,
    val error: String? = null,
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val chapter: Int = 0,
    // OFFSET is a SQL keyword, so the column carries a different name.
    @ColumnInfo(name = "scrollOffset") val offset: Float = 0f,
    val title: String = "",
    val at: Long = 0,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapter: Int,
    @ColumnInfo(name = "scrollOffset") val offset: Float,
    val title: String = "",
    val note: String = "",
    val at: Long = System.currentTimeMillis(),
)

@Entity(tableName = "annotations", indices = [Index("bookId")])
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapter: Int,
    @ColumnInfo(name = "scrollOffset") val offset: Float,
    val chapterTitle: String = "",
    val quote: String,
    val note: String = "",
    val at: Long = System.currentTimeMillis(),
)

@Entity(tableName = "reading_sessions", indices = [Index("bookId"), Index("startedAt")])
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val startedAt: Long,
    val durationSeconds: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val bookId: String,
)

@Entity(tableName = "custom_libraries")
data class CustomLibraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "library_book_joins",
    primaryKeys = ["libraryId", "bookId"],
    indices = [Index("bookId")],
)
data class LibraryBookJoinEntity(
    val libraryId: Long,
    val bookId: String,
)

/** A library row joined with whatever reading state exists for it. */
data class BookCard(
    val id: String,
    val docUri: String,
    val treeUri: String,
    val title: String,
    val author: String,
    val series: String,
    val language: String,
    val publisher: String,
    val description: String,
    val source: String,
    val filename: String,
    val folder: String,
    val chapters: Int,
    val size: Long,
    val added: Long,
    val broken: Boolean,
    val coverFile: String?,
    val chapter: Int?,
    val scrollOffset: Float?,
    val lastRead: Long?,
    val lastChapter: String?,
    val favorite: Boolean,
) {
    /** Whole-book completion, 0..100, matching the desktop app's calculation. */
    val percent: Int
        get() {
            if (chapters <= 0 || lastRead == null) return 0
            val read = (chapter ?: 0) + (scrollOffset ?: 0f)
            return ((read / chapters).coerceIn(0f, 1f) * 100).toInt()
        }

    val started: Boolean get() = lastRead != null && lastRead > 0
}
