package com.shelf.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.shelf.reader.epub.EpubBook
import com.shelf.reader.epub.EpubException
import com.shelf.reader.epub.EpubMeta
import com.shelf.reader.epub.EpubOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

data class BookDeletionResult(
    val success: Boolean,
    val message: String,
    val writePermissionRequest: FolderWritePermissionRequest? = null,
)

data class FolderWritePermissionRequest(
    val bookId: String,
    val treeUri: String,
    val sourceName: String,
)

class ShelfRepository(
    private val context: Context,
    val dao: ShelfDao,
    val scope: CoroutineScope,
) {
    val scanner = LibraryScanner(context, dao, scope)
    val settings = SettingsStore(context)

    /** Parsed books stay warm — reopening the archive per chapter is noticeably slower. */
    private val openBooks = object : LinkedHashMap<String, EpubBook>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpubBook>): Boolean {
            val evict = size > 4
            if (evict) runCatching { eldest.value.close() }
            return evict
        }
    }
    private val booksLock = Any()

    // ------------------------------------------------------------------ books

    fun observeCards(): Flow<List<BookCard>> = dao.observeCards()

    fun observeRoots(): Flow<List<RootEntity>> = dao.observeRoots()

    suspend fun bookEntity(id: String): BookEntity? = withContext(Dispatchers.IO) { dao.book(id) }

    /** Keep scanned metadata aligned with the parser when its classification rules improve. */
    suspend fun syncBookMetadata(id: String, meta: EpubMeta) = withContext(Dispatchers.IO) {
        val current = dao.book(id) ?: return@withContext
        val updated = current.copy(
            title = meta.title.ifBlank { current.title },
            author = meta.author,
            series = meta.series,
            language = meta.language,
            publisher = meta.publisher,
            description = meta.description,
            chapters = meta.chapters,
        )
        if (updated != current) dao.upsertBooks(listOf(updated))
    }

    suspend fun openBook(id: String): EpubBook = withContext(Dispatchers.IO) {
        synchronized(booksLock) { openBooks[id] }?.let { return@withContext it }

        val entry = dao.book(id) ?: throw EpubException("That book is not in the library")
        val uri = Uri.parse(entry.docUri)
        if (!documentExists(uri)) throw EpubException("The file is no longer at that location")

        val book = EpubOpener.open(context, uri)
        synchronized(booksLock) { openBooks[id] = book }
        book
    }

    /**
     * Permanently deletes the exact EPUB represented by [id], then removes all local
     * records. Local data is deliberately kept if the storage provider rejects deletion.
     */
    suspend fun deleteBookFromSource(id: String): BookDeletionResult = withContext(Dispatchers.IO) {
        scanner.cancelAndJoin()
        val entry = dao.book(id)
            ?: return@withContext BookDeletionResult(false, "That book is no longer in the library.")
        if (!entry.filename.endsWith(".epub", ignoreCase = true)) {
            return@withContext BookDeletionResult(false, "Shelf refused to delete a source that is not an EPUB file.")
        }

        releaseBook(id)
        val uri = runCatching { Uri.parse(entry.docUri) }.getOrNull()
            ?: return@withContext BookDeletionResult(false, "The source file location is invalid.")

        val deleted = try {
            when (uri.scheme?.lowercase()) {
                "content" -> {
                    if (!DocumentsContract.isDocumentUri(context, uri)) {
                        return@withContext BookDeletionResult(
                            false,
                            "The stored source is not a deletable Android document.",
                        )
                    }
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                }
                "file" -> {
                    val path = uri.path
                        ?: return@withContext BookDeletionResult(false, "The source file location is invalid.")
                    val source = File(path)
                    !source.exists() || (source.isFile && source.delete())
                }
                else -> return@withContext BookDeletionResult(
                    false,
                    "This storage provider does not support deleting files from Shelf.",
                )
            }
        } catch (_: FileNotFoundException) {
            true // It disappeared between the existence check and delete call.
        } catch (_: SecurityException) {
            if (!hasPersistedWriteAccess(entry.treeUri)) {
                return@withContext BookDeletionResult(
                    success = false,
                    message = "Shelf needs permission to change ${entry.source}.",
                    writePermissionRequest = FolderWritePermissionRequest(id, entry.treeUri, entry.source),
                )
            }
            return@withContext BookDeletionResult(false, "The storage provider denied deletion even though write access is enabled.")
        } catch (error: Throwable) {
            return@withContext BookDeletionResult(
                false,
                error.message?.let { "Could not delete the EPUB: $it" } ?: "The storage provider could not delete this EPUB.",
            )
        }

        if (!deleted) {
            return@withContext BookDeletionResult(false, "The storage provider refused to delete this EPUB.")
        }

        runCatching { dao.deleteBookData(id) }.onFailure { error ->
            return@withContext BookDeletionResult(
                true,
                "The EPUB was deleted, but Shelf could not clear its library entry: ${error.message.orEmpty()}. Refresh the library.",
            )
        }
        entry.coverFile?.let { File(scanner.coverDir, it).delete() }
        BookDeletionResult(true, "Deleted “${entry.title}” from ${entry.source}.")
    }

    /** Persist a user-approved write grant, but only when they picked the expected source tree. */
    suspend fun grantFolderWriteAccess(
        expectedTreeUri: String,
        selectedTreeUri: Uri,
    ): String? = withContext(Dispatchers.IO) {
        val expected = runCatching { Uri.parse(expectedTreeUri) }.getOrNull()
            ?: return@withContext "The original folder location is invalid."
        if (!sameDocumentTree(expected, selectedTreeUri)) {
            return@withContext "That is a different folder. Select the original source folder to continue."
        }

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(selectedTreeUri, flags)
        }.getOrElse {
            return@withContext "Android did not grant permission to change files in that folder."
        }
        if (!hasPersistedWriteAccess(expectedTreeUri)) {
            return@withContext "This storage provider gave Shelf read-only access. The EPUB was not deleted."
        }
        null
    }

    private fun hasPersistedWriteAccess(treeUri: String): Boolean {
        val expected = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isWritePermission && sameDocumentTree(expected, permission.uri)
        }
    }

    private fun sameDocumentTree(first: Uri, second: Uri): Boolean = runCatching {
        first.authority.equals(second.authority, ignoreCase = true) &&
            DocumentsContract.getTreeDocumentId(first) == DocumentsContract.getTreeDocumentId(second)
    }.getOrDefault(false)

    fun releaseBook(id: String) {
        synchronized(booksLock) { openBooks.remove(id) }?.let { runCatching { it.close() } }
    }

    fun releaseAll() {
        synchronized(booksLock) {
            openBooks.values.forEach { runCatching { it.close() } }
            openBooks.clear()
        }
    }

    private fun documentExists(uri: Uri): Boolean = runCatching {
        DocumentFile.fromSingleUri(context, uri)?.exists() == true
    }.getOrDefault(true)

    fun coverFile(name: String?): File? = scanner.coverFile(name)

    suspend fun exportBackup(uri: Uri): BackupSummary =
        ShelfBackup.export(context, dao, settings, uri)

    suspend fun restoreBackup(uri: Uri): BackupSummary =
        ShelfBackup.restore(context, dao, settings, uri)

    // ------------------------------------------------------------------ roots

    /**
     * Takes lasting permission on a folder the person picked and adds it to the library.
     * Returns an error message, or null when the folder was added.
     */
    suspend fun addRoot(treeUri: Uri): String? = withContext(Dispatchers.IO) {
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val readWriteFlags = readFlag or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, readWriteFlags)
        }.recoverCatching {
            // Read-only providers are still valid libraries; deletion will explain the limitation.
            context.contentResolver.takePersistableUriPermission(treeUri, readFlag)
        }
        if (persisted.isFailure) {
            return@withContext "Android would not grant lasting access to that folder. Pick it again from the folder chooser."
        }

        val key = treeUri.toString()
        if (dao.roots().any { it.treeUri == key }) return@withContext "That folder is already in the library."

        val name = DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: runCatching { DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast('/') }.getOrNull()
            ?: "Folder"

        dao.addRoot(RootEntity(treeUri = key, name = name))
        scanner.start()
        null
    }

    suspend fun removeRoot(treeUri: String) = withContext(Dispatchers.IO) {
        dao.booksForRoot(treeUri).forEach { book ->
            book.coverFile?.let { File(scanner.coverDir, it).delete() }
        }
        dao.removeRoot(treeUri)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        releaseAll()
    }

    suspend fun setRootEnabled(treeUri: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.setRootEnabled(treeUri, enabled)
        scanner.start()
    }

    fun rescan(force: Boolean = false) {
        releaseAll()
        scanner.start(force)
    }

    // --------------------------------------------------------------- progress

    suspend fun progress(bookId: String): ProgressEntity? =
        withContext(Dispatchers.IO) { dao.progress(bookId) }

    suspend fun remapProgressChapter(bookId: String, chapter: Int) =
        withContext(Dispatchers.IO) { dao.remapProgressChapter(bookId, chapter) }

    suspend fun remapSavedLocations(bookId: String, book: EpubBook) = withContext(Dispatchers.IO) {
        dao.bookmarksForBook(bookId).forEach { item ->
            val chapter = book.readingIndexForPackageIndex(item.chapter)
            if (chapter != item.chapter) dao.remapBookmarkChapter(item.id, chapter)
        }
        dao.annotationsForBook(bookId).forEach { item ->
            val chapter = book.readingIndexForPackageIndex(item.chapter)
            if (chapter != item.chapter) dao.remapAnnotationChapter(item.id, chapter)
        }
    }

    suspend fun saveProgress(bookId: String, chapter: Int, offset: Float, title: String) =
        withContext(Dispatchers.IO) {
            dao.saveProgress(
                ProgressEntity(
                    bookId = bookId,
                    chapter = chapter,
                    offset = offset.coerceIn(0f, 1f),
                    title = title,
                    at = System.currentTimeMillis(),
                )
            )
        }

    suspend fun clearProgress(bookId: String) = withContext(Dispatchers.IO) { dao.clearProgress(bookId) }

    // -------------------------------------------------------------- bookmarks

    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> = dao.observeBookmarks(bookId)

    suspend fun addBookmark(bookId: String, chapter: Int, offset: Float, title: String, note: String = "") =
        withContext(Dispatchers.IO) {
            dao.addBookmark(
                BookmarkEntity(
                    bookId = bookId, chapter = chapter,
                    offset = offset, title = title, note = note,
                )
            )
        }

    suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) { dao.deleteBookmark(id) }

    // ------------------------------------------------------------ annotations

    fun observeAnnotations(bookId: String): Flow<List<AnnotationEntity>> = dao.observeAnnotations(bookId)

    suspend fun addAnnotation(annotation: AnnotationEntity) = withContext(Dispatchers.IO) {
        dao.addAnnotation(annotation)
    }

    suspend fun deleteAnnotation(id: Long) = withContext(Dispatchers.IO) { dao.deleteAnnotation(id) }

    // ------------------------------------------------------- reading sessions

    fun observeReadingSessions(): Flow<List<ReadingSessionEntity>> = dao.observeReadingSessions()

    suspend fun recordReadingSession(bookId: String, startedAt: Long, durationSeconds: Long) =
        withContext(Dispatchers.IO) {
            dao.addReadingSession(
                ReadingSessionEntity(
                    bookId = bookId,
                    startedAt = startedAt,
                    durationSeconds = durationSeconds,
                )
            )
        }

    // -------------------------------------------------------------- favorites

    suspend fun toggleFavorite(bookId: String) = withContext(Dispatchers.IO) {
        if (dao.isFavorite(bookId)) dao.removeFavorite(bookId)
        else dao.addFavorite(FavoriteEntity(bookId))
    }

    // -------------------------------------------------------- custom libraries

    fun observeCustomLibraries(): Flow<List<CustomLibraryEntity>> = dao.observeCustomLibraries()

    suspend fun addCustomLibrary(name: String): Long = withContext(Dispatchers.IO) {
        dao.insertCustomLibrary(CustomLibraryEntity(name = name))
    }

    suspend fun removeCustomLibrary(id: Long) = withContext(Dispatchers.IO) {
        dao.clearLibrary(id)
        dao.deleteCustomLibrary(id)
    }

    suspend fun addBookToLibrary(libraryId: Long, bookId: String) = withContext(Dispatchers.IO) {
        dao.addBookToLibrary(LibraryBookJoinEntity(libraryId, bookId))
    }

    suspend fun removeBookFromLibrary(libraryId: Long, bookId: String) = withContext(Dispatchers.IO) {
        dao.removeBookFromLibrary(libraryId, bookId)
    }

    fun observeLibrariesForBook(bookId: String): Flow<List<Long>> = dao.observeLibrariesForBook(bookId)

    fun observeBookIdsInLibrary(libraryId: Long): Flow<List<String>> = dao.observeBookIdsInLibrary(libraryId)
}
