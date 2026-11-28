package com.shelf.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.shelf.reader.epub.EpubBook
import com.shelf.reader.epub.EpubOpener
import com.shelf.reader.epub.chapterKeys
import com.shelf.reader.epub.chapterMoves
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

data class ScanStatus(
    val running: Boolean = false,
    val found: Int = 0,
    val done: Int = 0,
    val current: String = "",
    val error: String = "",
) {
    val fraction: Float get() = if (found > 0) done.toFloat() / found else 0f
}

/**
 * Walks the folders the reader was given and keeps the library table in step.
 *
 * Files whose size and timestamp are unchanged keep their cached row, so a rescan over
 * a thousand books touches only what actually moved.
 */
class LibraryScanner(
    private val context: Context,
    private val dao: ShelfDao,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(ScanStatus())
    val status = _status.asStateFlow()

    private var job: Job? = null

    val coverDir: File by lazy { File(context.filesDir, "covers").apply { mkdirs() } }

    fun coverFile(name: String?): File? =
        name?.let { File(coverDir, it) }?.takeIf { it.exists() }

    fun start(force: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _status.value = ScanStatus(running = true, current = "Starting")
            try {
                scan(force)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _status.value = _status.value.copy(error = e.message ?: "Scan failed")
            } finally {
                _status.value = _status.value.copy(running = false, current = "")
            }
        }
    }

    suspend fun refreshOutdatedBooks() {
        if (dao.hasOutdatedBooks("$PARSER_REVISION:%")) start()
    }

    fun cancel() {
        job?.cancel()
    }

    suspend fun cancelAndJoin() {
        job?.cancelAndJoin()
        job = null
    }

    // ------------------------------------------------------------------ walk

    private data class Found(
        val source: String,
        val treeUri: String,
        val docUri: Uri,
        val name: String,
        val folder: String,
        val folderPath: List<String>,
        val size: Long,
        val modified: Long,
    )

    private data class PendingBook(
        val id: String,
        val item: Found,
        val stamp: String,
        val cached: BookEntity?,
        val catalogTags: List<String>,
        val editionKey: String,
    )

    private suspend fun scan(force: Boolean) {
        val roots = dao.roots().filter { it.enabled }
        val found = ArrayList<Found>()
        val completedRoots = HashSet<String>()
        val errors = ArrayList<String>()
        val catalogDocuments = HashMap<String, Uri>()

        for (root in roots) {
            currentCoroutineContext().ensureActive()
            _status.value = _status.value.copy(current = "Looking in ${root.name}")
            val treeUri = Uri.parse(root.treeUri)
            val startId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: continue
            try {
                walk(root, treeUri, startId, root.name, found, catalogDocuments)
                completedRoots.add(root.treeUri)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                errors.add("${root.name}: ${error.message ?: "Cannot list folder"}")
            }
        }

        val catalogs = HashMap<String, Map<String, TagCatalog.Edition>>()
        for (root in roots) {
            val catalogUri = catalogDocuments[root.treeUri]
            if (catalogUri == null) {
                if (root.treeUri in completedRoots) catalogs[root.treeUri] = emptyMap()
                continue
            }
            try {
                currentCoroutineContext().ensureActive()
                catalogs[root.treeUri] = context.contentResolver.openInputStream(catalogUri)?.use(TagCatalog::read)
                    ?: error("Cannot read ${TagCatalog.FILE_NAME}")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                errors.add("${root.name}: ${error.message ?: "Cannot read ${TagCatalog.FILE_NAME}"}")
            }
        }

        // Do not prune cached books for disabled, disconnected or partially unreadable roots.
        val cachedBooks = HashMap<String, BookEntity>()
        val existing = HashSet<String>()
        for (root in roots) {
            for (book in dao.booksForRoot(root.treeUri)) {
                currentCoroutineContext().ensureActive()
                cachedBooks[book.docUri] = book
                if (root.treeUri == IMPORTED_ROOT) {
                    // File-picker imports are already stored in Shelf's private directory.
                    val uri = Uri.parse(book.docUri)
                    val file = uri.path?.takeIf { uri.scheme == "file" }?.let(::File)
                    if (file?.isFile == true) {
                        found.add(Found(root.name, root.treeUri, uri, book.filename, book.folder,
                            emptyList(), file.length(), file.lastModified()))
                    }
                }
                if (root.treeUri in completedRoots &&
                    (root.includeSubfolders || BookFolders.decode(book.folderPath)?.isEmpty() == true ||
                        (book.folderPath == null && book.folder == root.name))) {
                    existing.add(book.id)
                }
            }
        }
        _status.value = _status.value.copy(found = found.size, done = 0)
        val seen = HashSet<String>(found.size)
        val currentUris = found.mapTo(HashSet()) { it.docUri.toString() }
        val editions = found.associate { item ->
            item.docUri.toString() to catalogs[item.treeUri]
                ?.get(TagCatalog.pathKey((item.folderPath + item.name).joinToString("/")))
        }
        val editionCounts = currentUris.groupingBy { uri -> editions[uri]?.key ?: cachedBooks[uri]?.editionKey.orEmpty() }.eachCount()
        val pastEditions = cachedBooks.values.filter {
            it.editionKey.isNotBlank() && it.docUri !in currentUris && (!it.available || it.id in existing)
        }.groupBy(BookEntity::editionKey)

        coroutineScope {
            found.chunked(64).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                val batch = ArrayList<BookEntity>()
                val moves = HashMap<String, Map<Int, Int>>()
                val pending = chunk.mapNotNull { item ->
                    val docUri = item.docUri.toString()
                    val edition = editions[docUri]
                    val editionKey = edition?.key ?: cachedBooks[docUri]?.editionKey.orEmpty()
                    val renamed = pastEditions[editionKey]?.singleOrNull()?.takeIf { editionCounts[editionKey] == 1 }
                    val cached = cachedBooks[docUri] ?: renamed
                    val id = cached?.id ?: bookId(docUri)
                    seen.add(id)
                    val stamp = "$PARSER_REVISION:${item.modified}:${item.size}"
                    val rootCatalog = catalogs[item.treeUri]
                    val tags = if (rootCatalog == null) cached?.catalogTags.orEmpty() else edition?.tags.orEmpty()

                    if (cached == null || cached.docUri != docUri || cached.stamp != stamp || cached.broken || force) {
                        PendingBook(id, item, stamp, cached, tags, editionKey)
                    } else {
                        val metadata = cached.copy(source = item.source, filename = item.name,
                            folder = item.folder, folderPath = BookFolders.encode(item.folderPath), catalogTags = tags,
                            editionKey = editionKey, available = true)
                        if (metadata != cached) batch.add(metadata)
                        null
                    }
                }
                // Limit concurrent EPUB reads while committing larger batches to Room.
                for (group in pending.chunked(8)) {
                    val parsed = group.map { pending -> async { readBook(pending, force) } }.awaitAll()
                    group.zip(parsed).forEach { (request, book) ->
                        batch += book
                        val remap = chapterMoves(request.cached?.chapterKeys.orEmpty(), book.chapterKeys)
                        if (remap.isNotEmpty()) moves[book.id] = remap
                    }
                }

                if (batch.isNotEmpty()) dao.indexBooks(batch, moves)
                _status.value = _status.value.copy(done = _status.value.done + chunk.size)
            }
        }

        val gone = cachedBooks.values.filter { it.available && it.id in existing && it.id !in seen }
        gone.filter { it.editionKey.isNotEmpty() }.map(BookEntity::id).chunked(400).forEach { dao.markBooksMissing(it) }
        val unmapped = gone.filter { it.editionKey.isEmpty() }.map(BookEntity::id)
        unmapped.chunked(400).forEach { dao.deleteBooks(it) }
        unmapped.forEach { File(coverDir, "$it.jpg").delete() }
        if (errors.isNotEmpty()) _status.value = _status.value.copy(error = errors.joinToString("\n"))
    }

    private suspend fun walk(
        root: RootEntity,
        treeUri: Uri,
        documentId: String,
        folderName: String,
        out: MutableList<Found>,
        catalogDocuments: MutableMap<String, Uri>,
        depth: Int = 0,
        folderPath: List<String> = emptyList(),
        visited: MutableSet<String> = HashSet(),
    ) {
        check(depth <= 64) { "Folder nesting exceeds 64 levels" }
        check(visited.add(documentId)) { "Storage provider returned a folder cycle" }
        currentCoroutineContext().ensureActive()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val subfolders = ArrayList<Pair<String, String>>()

        val children = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: error("Storage provider could not list $folderName")
        children.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val childId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty()
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name.startsWith(".")) continue
                    if (root.includeSubfolders) subfolders.add(childId to name)
                    continue
                }
                if (depth == 0 && name.equals(TagCatalog.FILE_NAME, ignoreCase = true)) {
                    catalogDocuments[root.treeUri] = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    continue
                }
                if (!name.endsWith(".epub", ignoreCase = true) || name.startsWith(".")) continue

                out.add(
                    Found(
                        source = root.name,
                        treeUri = root.treeUri,
                        docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                        name = name,
                        folder = folderName,
                        folderPath = folderPath,
                        size = size,
                        modified = modified,
                    )
                )
            }
        }

        // Recurse after closing the cursor so we never hold many open at once.
        for ((childId, name) in subfolders) {
            walk(root, treeUri, childId, name, out, catalogDocuments, depth + 1, folderPath + name, visited)
        }
    }

    // ---------------------------------------------------------------- metadata

    private fun readBook(pending: PendingBook, force: Boolean): BookEntity {
        val (id, item, stamp, existing, catalogTags, editionKey) = pending
        val base = BookEntity(
            id = id,
            docUri = item.docUri.toString(),
            treeUri = item.treeUri,
            source = item.source,
            filename = item.name,
            folder = item.folder,
            folderPath = BookFolders.encode(item.folderPath),
            stamp = stamp,
            size = item.size,
            added = item.modified / 1000,
            title = item.name.substringBeforeLast('.'),
            coverFile = existing?.coverFile,
            subjects = existing?.subjects.orEmpty(),
            catalogTags = catalogTags,
            editionKey = editionKey,
            chapterKeys = existing?.chapterKeys.orEmpty(),
        )

        var book: EpubBook? = null
        return try {
            book = EpubOpener.open(context, item.docUri, stamp, force)
            val meta = book.meta
            val coverName = book.cover()?.let { (bytes, _) -> writeCover(id, bytes) } ?: base.coverFile
            base.copy(
                title = meta.title.ifBlank { base.title },
                author = meta.author,
                series = meta.series,
                language = meta.language,
                publisher = meta.publisher,
                description = meta.description,
                subjects = cleanBookTags(meta.subjects),
                chapters = meta.chapters,
                chapterKeys = book.chapterKeys(),
                coverFile = coverName,
                broken = false,
                error = null,
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            base.copy(broken = true, error = e.message?.take(200))
        } finally {
            runCatching { book?.close() }
        }
    }

    internal suspend fun indexImport(id: String, file: File, filename: String) {
        val source = "Imported EPUBs"
        val item = Found(source, IMPORTED_ROOT, Uri.fromFile(file), filename, source, emptyList(), file.length(), file.lastModified())
        val entry = readBook(PendingBook(id, item, "$PARSER_REVISION:${item.modified}:${item.size}", null, emptyList(), ""), false)
        if (entry.broken || entry.chapters == 0) {
            coverFile(entry.coverFile)?.delete()
            throw IllegalArgumentException(entry.error ?: "This EPUB has no readable chapters.")
        }
        dao.indexImportedBook(entry)
    }

    /** Covers are stored downscaled — a thousand full-size jpegs is a lot of storage. */
    private fun writeCover(id: String, bytes: ByteArray): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        val target = 520
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@runCatching null

        val name = "$id.jpg"
        File(coverDir, name).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        bitmap.recycle()
        name
    }.getOrNull()

    companion object {
        const val IMPORTED_ROOT = "shelf:imports"
        /** Bump when metadata/chapter classification changes so a normal refresh reparses books. */
        private const val PARSER_REVISION = 5

        fun bookId(uri: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            val hex = "0123456789abcdef"
            return buildString(16) {
                for (index in 0 until 8) {
                    val byte = digest[index].toInt() and 0xff
                    append(hex[byte ushr 4])
                    append(hex[byte and 0xf])
                }
            }
        }
    }
}
