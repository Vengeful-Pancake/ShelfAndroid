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
        val size: Long,
        val modified: Long,
    )

    private suspend fun scan(force: Boolean) {
        val roots = dao.roots().filter { it.enabled }
        val found = ArrayList<Found>()

        for (root in roots) {
            currentCoroutineContext().ensureActive()
            _status.value = _status.value.copy(current = "Looking in ${root.name}")
            val treeUri = Uri.parse(root.treeUri)
            val startId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: continue
            walk(root, treeUri, startId, root.name, found)
        }

        _status.value = _status.value.copy(found = found.size, done = 0)

        val existing = dao.allBookIds().toMutableSet()
        val seen = HashSet<String>(found.size)

        coroutineScope {
            found.chunked(16).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                val jobs = chunk.map { item ->
                    async(Dispatchers.IO) {
                        val id = bookId(item.docUri.toString())
                        val stamp = "$PARSER_REVISION:${item.modified}:${item.size}"

                        val cached = dao.book(id)
                        if (cached != null && cached.stamp == stamp && !cached.broken && !force) {
                            id to null
                        } else {
                            id to readBook(id, item, stamp, cached)
                        }
                    }
                }

                val results = jobs.awaitAll()
                val batch = mutableListOf<BookEntity>()
                results.forEach { (id, entity) ->
                    seen.add(id)
                    if (entity != null) batch.add(entity)
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
    }

    private suspend fun walk(
        root: RootEntity,
        treeUri: Uri,
        documentId: String,
        folderName: String,
        out: MutableList<Found>,
        depth: Int = 0,
    ) {
        if (depth > 12) return
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

        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        }.getOrNull()?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty()
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name.startsWith(".") || name.startsWith("@") || name.startsWith("#")) continue
                    subfolders.add(childId to name)
                    continue
                }
                if (!name.lowercase().endsWith(".epub") || name.startsWith(".")) continue

                out.add(
                    Found(
                        source = root.name,
                        treeUri = root.treeUri,
                        docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                        name = name,
                        folder = folderName,
                        size = size,
                        modified = modified / 1000,
                    )
                )
            }
        }

        // Recurse after closing the cursor so we never hold many open at once.
        for ((childId, name) in subfolders) {
            walk(root, treeUri, childId, name, out, depth + 1)
        }
    }

    // ---------------------------------------------------------------- metadata

    private fun readBook(id: String, item: Found, stamp: String, existing: BookEntity? = null): BookEntity {
        val base = BookEntity(
            id = id,
            docUri = item.docUri.toString(),
            treeUri = item.treeUri,
            source = item.source,
            filename = item.name,
            folder = item.folder,
            stamp = stamp,
            size = item.size,
            added = item.modified,
            title = item.name.substringBeforeLast('.'),
            coverFile = existing?.coverFile,
        )

        var book: EpubBook? = null
        return try {
            book = EpubOpener.open(context, item.docUri)
            val meta = book.meta
            val coverName = book.cover()?.let { (bytes, _) -> writeCover(id, bytes) } ?: base.coverFile
            base.copy(
                title = meta.title.ifBlank { base.title },
                author = meta.author,
                series = meta.series,
                language = meta.language,
                publisher = meta.publisher,
                description = meta.description,
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
        private const val PARSER_REVISION = 2

        fun bookId(uri: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
