package com.shelf.reader.epub

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Turns a Storage Access Framework document into an [EpubBook].
 *
 * Most providers hand back a real seekable file descriptor, which lets the reader pull
 * single chapters straight out of the archive. Providers that only give a stream — some
 * cloud and network mounts do — fall back to a one-off copy into the cache directory.
 */
object EpubOpener {

    private const val CACHE_DIR = "epub-cache"
    private const val CACHE_BUDGET_BYTES = 400L * 1024 * 1024

    private val cacheLock = Any()

    fun open(context: Context, uri: Uri, version: String, refreshCache: Boolean = false): EpubBook {
        val resolver = context.contentResolver

        // Fast path: seek directly inside the document.
        runCatching {
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw EpubException("The file could not be opened")
            try {
                val stream = FileInputStream(pfd.fileDescriptor)
                val channel = stream.channel
                if (channel.size() > 0) {
                    val closer = Closeable {
                        runCatching { stream.close() }
                        runCatching { pfd.close() }
                    }
                    return EpubBook(ZipReader.open(channel, closer))
                }
                runCatching { stream.close() }
                runCatching { pfd.close() }
            } catch (e: Throwable) {
                runCatching { pfd.close() }
                throw e
            }
        }.onFailure { if (it is EpubException) throw it }

        // Slow path: copy once, then read from the copy.
        val copy = synchronized(cacheLock) { cacheCopy(context, uri, version, refreshCache) }
        val raf = RandomAccessFile(copy, "r")
        val closer = Closeable { runCatching { raf.close() } }
        return EpubBook(ZipReader.open(raf.channel, closer))
    }

    private fun cacheCopy(context: Context, uri: Uri, version: String, refresh: Boolean): File {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest("$uri\n$version".toByteArray())
        val name = android.util.Base64.encodeToString(key, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP).trimEnd('=') + ".epub"
        val target = File(dir, name)
        if (!refresh && target.exists() && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val temp = File.createTempFile("epub-", ".tmp", dir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            } ?: throw EpubException("The file could not be opened")
            if (!temp.renameTo(target)) throw EpubException("Could not cache the EPUB")
        } finally {
            temp.delete()
        }
        trimCache(dir, target)
        return target
    }

    /** Keep the copy directory under a sane size — oldest first. */
    private fun trimCache(dir: File, current: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= CACHE_BUDGET_BYTES) break
            if (file == current) continue
            total -= file.length()
            file.delete()
        }
    }

    fun clearCache(context: Context) {
        File(context.cacheDir, CACHE_DIR).deleteRecursively()
    }
}
