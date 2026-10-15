package com.shelf.reader.epub

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/**
 * A read-only ZIP reader that works over any seekable channel.
 *
 * Android's [java.util.zip.ZipFile] needs a real file path, which we do not have for
 * documents handed to us through the Storage Access Framework. Streaming with
 * ZipInputStream would mean inflating the whole archive to reach one chapter, so this
 * reads the central directory once and then seeks straight to the bytes it needs.
 */
class ZipReader private constructor(
    private val channel: FileChannel,
    private val onClose: Closeable?,
) : Closeable {

    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long,
    )

    /** Entry name -> entry, in central-directory order. */
    val entries: LinkedHashMap<String, Entry> = LinkedHashMap()

    /** Lower-cased name -> real name, so we can survive case-sloppy EPUBs. */
    private val lowerIndex = HashMap<String, String>()

    val names: Collection<String> get() = entries.keys

    companion object {
        private const val EOCD_SIG = 0x06054b50
        private const val ZIP64_EOCD_LOCATOR_SIG = 0x07064b50
        private const val ZIP64_EOCD_SIG = 0x06064b50
        private const val CEN_SIG = 0x02014b50
        private const val LOC_SIG = 0x04034b50
        private const val MAX_COMMENT = 0xFFFF

        fun open(channel: FileChannel, onClose: Closeable? = null): ZipReader =
            ZipReader(channel, onClose).apply { readCentralDirectory() }
    }

    // ------------------------------------------------------------------ read

    private fun readAt(position: Long, length: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var read = 0
        while (read < length) {
            val n = channel.read(buf, position + read)
            if (n <= 0) break
            read += n
        }
        if (read < length) throw IOException("Unexpected end of archive at $position")
        buf.flip()
        return buf
    }

    private fun readCentralDirectory() {
        val fileSize = channel.size()
        if (fileSize < 22) throw IOException("File is too small to be a zip archive")

        // Walk backwards over the possible comment area looking for the EOCD record.
        val tailLength = minOf(fileSize, (MAX_COMMENT + 22).toLong()).toInt()
        val tailStart = fileSize - tailLength
        val tail = readAt(tailStart, tailLength)
        var eocd = -1
        for (i in tailLength - 22 downTo 0) {
            if (tail.getInt(i) == EOCD_SIG) { eocd = i; break }
        }
        if (eocd < 0) throw IOException("Not a zip archive (no end-of-central-directory record)")

        var entryCount = (tail.getShort(eocd + 10).toInt() and 0xFFFF).toLong()
        var cenSize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        var cenOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL

        // ZIP64: the 32-bit fields saturate and the real values live in a separate record.
        if (entryCount == 0xFFFFL || cenOffset == 0xFFFFFFFFL || cenSize == 0xFFFFFFFFL) {
            val locatorPos = tailStart + eocd - 20
            if (locatorPos >= 0) {
                val locator = readAt(locatorPos, 20)
                if (locator.getInt(0) == ZIP64_EOCD_LOCATOR_SIG) {
                    val z64Offset = locator.getLong(8)
                    val z64 = readAt(z64Offset, 56)
                    if (z64.getInt(0) == ZIP64_EOCD_SIG) {
                        entryCount = z64.getLong(32)
                        cenSize = z64.getLong(40)
                        cenOffset = z64.getLong(48)
                    }
                }
            }
        }

        if (cenOffset < 0 || cenOffset + cenSize > fileSize) {
            throw IOException("Central directory is outside the file")
        }

        val cen = readAt(cenOffset, cenSize.toInt())
        var p = 0
        var i = 0L
        while (i < entryCount && p + 46 <= cen.limit()) {
            if (cen.getInt(p) != CEN_SIG) break
            val method = cen.getShort(p + 10).toInt() and 0xFFFF
            var compressed = cen.getInt(p + 20).toLong() and 0xFFFFFFFFL
            var uncompressed = cen.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cen.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cen.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = cen.getShort(p + 32).toInt() and 0xFFFF
            var localOffset = cen.getInt(p + 42).toLong() and 0xFFFFFFFFL

            val nameBytes = ByteArray(nameLen)
            for (k in 0 until nameLen) nameBytes[k] = cen.get(p + 46 + k)
            val name = String(nameBytes, Charsets.UTF_8)

            // ZIP64 extended information extra field (0x0001)
            if (uncompressed == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                var e = p + 46 + nameLen
                val extraEnd = e + extraLen
                while (e + 4 <= extraEnd) {
                    val headerId = cen.getShort(e).toInt() and 0xFFFF
                    val dataSize = cen.getShort(e + 2).toInt() and 0xFFFF
                    var q = e + 4
                    if (headerId == 0x0001) {
                        if (uncompressed == 0xFFFFFFFFL && q + 8 <= extraEnd) { uncompressed = cen.getLong(q); q += 8 }
                        if (compressed == 0xFFFFFFFFL && q + 8 <= extraEnd) { compressed = cen.getLong(q); q += 8 }
                        if (localOffset == 0xFFFFFFFFL && q + 8 <= extraEnd) { localOffset = cen.getLong(q) }
                        break
                    }
                    e += 4 + dataSize
                }
            }

            if (name.isNotEmpty() && !name.endsWith("/")) {
                val clean = name.trimStart('/')
                val entry = Entry(clean, method, compressed, uncompressed, localOffset)
                entries[clean] = entry
                lowerIndex.putIfAbsent(clean.lowercase(), clean)
            }

            p += 46 + nameLen + extraLen + commentLen
            i++
        }

        if (entries.isEmpty()) throw IOException("Archive contains no files")
    }

    // ---------------------------------------------------------------- lookup

    /** Resolve a path tolerantly: exact, then case-insensitive, then backslash-normalised. */
    fun resolve(path: String): String? {
        val clean = path.trimStart('/')
        if (entries.containsKey(clean)) return clean
        lowerIndex[clean.lowercase()]?.let { return it }
        val swapped = clean.replace('\\', '/')
        if (entries.containsKey(swapped)) return swapped
        return lowerIndex[swapped.lowercase()]
    }

    fun has(path: String): Boolean = resolve(path) != null

    // ----------------------------------------------------------------- bytes

    fun read(path: String): ByteArray {
        val name = resolve(path) ?: throw IOException("Missing entry: $path")
        val entry = entries.getValue(name)

        // The local header repeats the name/extra lengths, and they can differ from
        // the central directory, so the data offset has to be computed from it.
        val local = readAt(entry.localHeaderOffset, 30)
        if (local.getInt(0) != LOC_SIG) throw IOException("Damaged local header for $name")
        val nameLen = local.getShort(26).toInt() and 0xFFFF
        val extraLen = local.getShort(28).toInt() and 0xFFFF
        val dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen

        return when (entry.method) {
            0 -> readAt(dataOffset, entry.size.toInt()).let { buf ->
                ByteArray(entry.size.toInt()).also { buf.get(it) }
            }
            8 -> inflate(dataOffset, entry.compressedSize, entry.size)
            else -> throw IOException("Unsupported compression method ${entry.method} for $name")
        }
    }

    fun readText(path: String): String {
        val bytes = read(path)
        // Strip a UTF-8 BOM; some EPUB toolchains leave one on the OPF.
        val start = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    private fun inflate(dataOffset: Long, compressedSize: Long, expectedSize: Long): ByteArray {
        val inflater = Inflater(true)
        try {
            val out = if (expectedSize in 1..Int.MAX_VALUE.toLong()) {
                java.io.ByteArrayOutputStream(expectedSize.toInt())
            } else {
                java.io.ByteArrayOutputStream(16 * 1024)
            }
            val inBuf = ByteArray(64 * 1024)
            val outBuf = ByteArray(64 * 1024)
            var remaining = compressedSize
            var position = dataOffset
            var stalls = 0

            while (true) {
                if (inflater.needsInput()) {
                    if (remaining <= 0) break
                    val chunk = minOf(remaining, inBuf.size.toLong()).toInt()
                    val bb = ByteBuffer.wrap(inBuf, 0, chunk)
                    var read = 0
                    while (read < chunk) {
                        val n = channel.read(bb, position + read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read <= 0) break
                    inflater.setInput(inBuf, 0, read)
                    position += read
                    remaining -= read
                }
                val n = inflater.inflate(outBuf)
                if (n > 0) {
                    out.write(outBuf, 0, n)
                    stalls = 0
                } else if (inflater.finished() || inflater.needsDictionary()) {
                    break
                } else if (inflater.needsInput() && remaining <= 0) {
                    break
                } else if (++stalls > 2) {
                    // Neither progress nor a clean finish: a truncated entry.
                    break
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { onClose?.close() }
    }
}
