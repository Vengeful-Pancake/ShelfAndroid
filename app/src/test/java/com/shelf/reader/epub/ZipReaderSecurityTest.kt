package com.shelf.reader.epub

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ZipReaderSecurityTest {
    private fun archive(mutate: (ByteArray) -> Unit = {}, action: (File) -> Unit) {
        val file = File.createTempFile("shelf-security", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("chapter.txt"))
                zip.write("Chinese chapter text".toByteArray())
                zip.closeEntry()
            }
            val bytes = file.readBytes()
            mutate(bytes)
            file.writeBytes(bytes)
            action(file)
        } finally { file.delete() }
    }

    private fun central(bytes: ByteArray): Int = (0 until bytes.size - 4).first {
        bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte()
    }

    @Test fun ordinaryZipStillReads() = archive { file ->
        RandomAccessFile(file, "r").use { source -> ZipReader.open(source.channel).use {
            assertEquals("Chinese chapter text", it.readText("chapter.txt"))
        } }
    }

    @Test fun rejectsHugeDeclaredUncompressedEntry() = archive({ bytes ->
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(central(bytes) + 24, 64 * 1024 * 1024)
    }) { file ->
        RandomAccessFile(file, "r").use { source -> ZipReader.open(source.channel).use { zip ->
            assertThrows(IOException::class.java) { zip.read("chapter.txt") }
        } }
    }

    @Test fun rejectsInflationBeyondDeclaredSize() = archive({ bytes ->
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(central(bytes) + 24, 1)
    }) { file ->
        RandomAccessFile(file, "r").use { source -> ZipReader.open(source.channel).use { zip ->
            assertThrows(IOException::class.java) { zip.read("chapter.txt") }
        } }
    }

    @Test fun rejectsHugeCentralDirectoryWithoutAllocatingIt() = archive({ bytes ->
        val end = bytes.size - 22
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(end + 12, Int.MAX_VALUE)
    }) { file ->
        RandomAccessFile(file, "r").use { source ->
            assertThrows(IOException::class.java) { ZipReader.open(source.channel) }
        }
    }
}
