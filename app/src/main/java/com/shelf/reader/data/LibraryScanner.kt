package com.shelf.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.shelf.reader.epub.EpubBook
import com.shelf.reader.epub.EpubOpener
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

        val catalogs = HashMap<String, Map<String, List<String>>>()
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

        _status.value = _status.value.copy(found = found.size, done = 0)

        // Do not prune cached books for disabled, disconnected or partially unreadable roots.
        val cachedBooks = HashMap<String, BookEntity>()
        val existing = HashSet<String>()
        for (root in roots) {
            for (book in dao.booksForRoot(root.treeUri)) {
                cachedBooks[book.docUri] = book
                if (root.treeUri in completedRoots &&
                    (root.includeSubfolders || BookFolders.decode(book.folderPath)?.isEmpty() == true ||
                        (book.folderPath == null && book.folder == root.name))) {
                    existing.add(book.id)
                }
            }
        }
        val seen = HashSet<String>(found.size)

        coroutineScope {
            found.chunked(64).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                val batch = ArrayList<BookEntity>()
                val pending = chunk.mapNotNull { item ->
                    val docUri = item.docUri.toString()
                    val cached = cachedBooks[docUri]
                    val id = cached?.id ?: bookId(docUri)
                    seen.add(id)
                    val stamp = "$PARSER_REVISION:${item.modified}:${item.size}"
                    val rootCatalog = catalogs[item.treeUri]
                    val tags = when {
                        rootCatalog == null -> cached?.catalogTags.orEmpty()
                        rootCatalog.isEmpty() -> emptyList()
                        else -> rootCatalog[TagCatalog.pathKey((item.folderPath + item.name).joinToString("/"))].orEmpty()
                    }

                    if (cached == null || cached.stamp != stamp || cached.broken || force) {
                        PendingBook(id, item, stamp, cached, tags)
                    } else {
                        val metadata = cached.copy(source = item.source, filename = item.name,
                            folder = item.folder, folderPath = BookFolders.encode(item.folderPath), catalogTags = tags)
                        if (metadata != cached) batch.add(metadata)
                        null
                    }
                }
                // Limit concurrent EPUB reads while committing larger batches to Room.
                for (group in pending.chunked(8)) {
                    batch += group.map { pending ->
                        async { readBook(pending, force) }
                    }.awaitAll()
                }

                if (batch.isNotEmpty()) dao.upsertBooks(batch)
                _status.value = _status.value.copy(done = _status.value.done + chunk.size)
            }
        }

        // Anything that vanished from disk leaves the library, along with its cover.
        val gone = existing - seen
        if (gone.isNotEmpty()) {
            gone.chunked(400).forEach { dao.deleteBooks(it) }
            gone.forEach { File(coverDir, "$it.jpg").delete() }
        }
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
        val (id, item, stamp, existing, catalogTags) = pending
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
        /** Bump when metadata/chapter classification changes so a normal refresh reparses books. */
        private const val PARSER_REVISION = 3

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
