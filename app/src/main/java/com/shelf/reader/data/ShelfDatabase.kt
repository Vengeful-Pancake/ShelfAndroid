package com.shelf.reader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfDao {

    // ------------------------------------------------------------------ roots

    @Query("SELECT * FROM roots ORDER BY addedAt ASC")
    fun observeRoots(): Flow<List<RootEntity>>

    @Query("SELECT * FROM roots ORDER BY addedAt ASC")
    suspend fun roots(): List<RootEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRoot(root: RootEntity)

    @Query("DELETE FROM roots WHERE treeUri = :treeUri")
    suspend fun deleteRoot(treeUri: String)

    @Query("DELETE FROM books WHERE treeUri = :treeUri")
    suspend fun deleteBooksForRoot(treeUri: String)

    @Query("UPDATE roots SET enabled = :enabled WHERE treeUri = :treeUri")
    suspend fun setRootEnabled(treeUri: String, enabled: Boolean)

    @Transaction
    suspend fun removeRoot(treeUri: String) {
        deleteBooksForRoot(treeUri)
        deleteRoot(treeUri)
    }

    // ------------------------------------------------------------------ books

    @Query(
        """
        SELECT b.id, b.docUri, b.treeUri, b.title, b.author, b.series,
               b.language, b.publisher, b.description, b.source, b.filename, b.folder,
               b.chapters, b.size, b.added, b.broken, b.coverFile,
               p.chapter AS chapter, p.scrollOffset AS scrollOffset, p.at AS lastRead,
               p.title AS lastChapter,
               CASE WHEN f.bookId IS NULL THEN 0 ELSE 1 END AS favorite
        FROM books b
        LEFT JOIN progress p ON p.bookId = b.id
        LEFT JOIN favorites f ON f.bookId = b.id
        """
    )
    fun observeCards(): Flow<List<BookCard>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: String): Flow<BookEntity?>

    @Query("SELECT id FROM books")
    suspend fun allBookIds(): List<String>

    @Query("SELECT * FROM books WHERE treeUri = :treeUri")
    suspend fun booksForRoot(treeUri: String): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooks(books: List<BookEntity>)

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteBooks(ids: List<String>)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun bookCount(): Int

    // --------------------------------------------------------------- progress

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun progress(bookId: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ProgressEntity)

    @Query("UPDATE progress SET chapter = :chapter WHERE bookId = :bookId")
    suspend fun remapProgressChapter(bookId: String, chapter: Int)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun clearProgress(bookId: String)

    // -------------------------------------------------------------- bookmarks

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY at DESC")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId")
    suspend fun bookmarksForBook(bookId: String): List<BookmarkEntity>

    @Insert
    suspend fun addBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksForBook(bookId: String)

    @Query("UPDATE bookmarks SET chapter = :chapter WHERE id = :id")
    suspend fun remapBookmarkChapter(id: Long, chapter: Int)

    @Query("SELECT * FROM bookmarks")
    suspend fun allBookmarks(): List<BookmarkEntity>

    @Insert
    suspend fun addBookmarks(bookmarks: List<BookmarkEntity>)

    // ------------------------------------------------------------ annotations

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY at DESC")
    fun observeAnnotations(bookId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE bookId = :bookId")
    suspend fun annotationsForBook(bookId: String): List<AnnotationEntity>

    @Insert
    suspend fun addAnnotation(annotation: AnnotationEntity): Long

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteAnnotation(id: Long)

    @Query("DELETE FROM annotations WHERE bookId = :bookId")
    suspend fun deleteAnnotationsForBook(bookId: String)

    @Query("UPDATE annotations SET chapter = :chapter WHERE id = :id")
    suspend fun remapAnnotationChapter(id: Long, chapter: Int)

    @Query("SELECT * FROM annotations")
    suspend fun allAnnotations(): List<AnnotationEntity>

    @Insert
    suspend fun addAnnotations(annotations: List<AnnotationEntity>)

    // ------------------------------------------------------- reading sessions

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt DESC")
    fun observeReadingSessions(): Flow<List<ReadingSessionEntity>>

    @Insert
    suspend fun addReadingSession(session: ReadingSessionEntity)

    @Insert
    suspend fun addReadingSessions(sessions: List<ReadingSessionEntity>)

    @Query("SELECT * FROM reading_sessions")
    suspend fun allReadingSessions(): List<ReadingSessionEntity>

    @Query("DELETE FROM reading_sessions WHERE bookId = :bookId")
    suspend fun deleteReadingSessionsForBook(bookId: String)

    // -------------------------------------------------------------- favorites

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE bookId = :bookId")
    suspend fun removeFavorite(bookId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE bookId = :bookId)")
    suspend fun isFavorite(bookId: String): Boolean

    @Query("SELECT * FROM favorites")
    suspend fun allFavorites(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorites(favorites: List<FavoriteEntity>)

    // -------------------------------------------------------- custom libraries

    @Query("SELECT * FROM custom_libraries ORDER BY name ASC")
    fun observeCustomLibraries(): Flow<List<CustomLibraryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomLibrary(library: CustomLibraryEntity): Long

    @Query("DELETE FROM custom_libraries WHERE id = :id")
    suspend fun deleteCustomLibrary(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookToLibrary(join: LibraryBookJoinEntity)

    @Query("DELETE FROM library_book_joins WHERE libraryId = :libraryId AND bookId = :bookId")
    suspend fun removeBookFromLibrary(libraryId: Long, bookId: String)

    @Query("DELETE FROM library_book_joins WHERE bookId = :bookId")
    suspend fun removeBookFromAllLibraries(bookId: String)

    @Query("SELECT libraryId FROM library_book_joins WHERE bookId = :bookId")
    fun observeLibrariesForBook(bookId: String): Flow<List<Long>>

    @Query("SELECT bookId FROM library_book_joins WHERE libraryId = :libraryId")
    fun observeBookIdsInLibrary(libraryId: Long): Flow<List<String>>

    @Query("DELETE FROM library_book_joins WHERE libraryId = :libraryId")
    suspend fun clearLibrary(libraryId: Long)

    @Query("SELECT * FROM custom_libraries")
    suspend fun allCustomLibraries(): List<CustomLibraryEntity>

    @Query("SELECT * FROM library_book_joins")
    suspend fun allLibraryJoins(): List<LibraryBookJoinEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addLibraryJoins(joins: List<LibraryBookJoinEntity>)

    /** Remove every local record owned by one book as a single database operation. */
    @Transaction
    suspend fun deleteBookData(bookId: String) {
        clearProgress(bookId)
        deleteBookmarksForBook(bookId)
        deleteAnnotationsForBook(bookId)
        deleteReadingSessionsForBook(bookId)
        removeFavorite(bookId)
        removeBookFromAllLibraries(bookId)
        deleteBook(bookId)
    }

    // --------------------------------------------------------------- backups

    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM progress")
    suspend fun allProgress(): List<ProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgressItems(progress: List<ProgressEntity>)

    @Query("DELETE FROM progress")
    suspend fun clearAllProgress()

    @Query("DELETE FROM bookmarks")
    suspend fun clearAllBookmarks()

    @Query("DELETE FROM annotations")
    suspend fun clearAllAnnotations()

    @Query("DELETE FROM favorites")
    suspend fun clearAllFavorites()

    @Query("DELETE FROM library_book_joins")
    suspend fun clearAllLibraryJoins()

    @Query("DELETE FROM custom_libraries")
    suspend fun clearAllCustomLibraries()

    @Query("DELETE FROM reading_sessions")
    suspend fun clearAllReadingSessions()

    @Transaction
    suspend fun replacePortableData(
        progress: List<ProgressEntity>,
        bookmarks: List<BookmarkEntity>,
        annotations: List<AnnotationEntity>,
        favorites: List<FavoriteEntity>,
        libraries: List<CustomLibraryEntity>,
        joins: List<LibraryBookJoinEntity>,
        sessions: List<ReadingSessionEntity>,
    ) {
        clearAllProgress()
        clearAllBookmarks()
        clearAllAnnotations()
        clearAllFavorites()
        clearAllLibraryJoins()
        clearAllCustomLibraries()
        clearAllReadingSessions()
        saveProgressItems(progress)
        addBookmarks(bookmarks)
        addAnnotations(annotations)
        addFavorites(favorites)
        libraries.forEach { insertCustomLibrary(it) }
        addLibraryJoins(joins)
        addReadingSessions(sessions)
    }
}

@Database(
    entities = [
        RootEntity::class,
        BookEntity::class,
        ProgressEntity::class,
        BookmarkEntity::class,
        AnnotationEntity::class,
        ReadingSessionEntity::class,
        FavoriteEntity::class,
        CustomLibraryEntity::class,
        LibraryBookJoinEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ShelfDatabase : RoomDatabase() {
    abstract fun dao(): ShelfDao

    companion object {
        @Volatile private var instance: ShelfDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS annotations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId TEXT NOT NULL,
                        chapter INTEGER NOT NULL,
                        scrollOffset REAL NOT NULL,
                        chapterTitle TEXT NOT NULL,
                        quote TEXT NOT NULL,
                        note TEXT NOT NULL,
                        at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_annotations_bookId ON annotations(bookId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        durationSeconds INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_sessions_bookId ON reading_sessions(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_sessions_startedAt ON reading_sessions(startedAt)")
            }
        }

        fun get(context: Context): ShelfDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ShelfDatabase::class.java,
                "shelf.db",
            ).addMigrations(MIGRATION_2_3).build().also { instance = it }
        }
    }
}
