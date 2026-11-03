package com.gammapdf.gamma.shell

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The files opened in the app (shell/OpenedFiles.kt), ported from
 * ipad/GammaIPadTests/OpenedFilesTests.swift: held as copies until the page
 * is done with them, listed, and read back in chunks that put the file
 * together again. Reading a content URI is the intake's (OpenedIntake); here
 * a file is the stream it gives.
 */
class OpenedFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun store() = OpenedFiles(File(temp.root, "Opened"))

    private fun source(name: String, bytes: ByteArray): File =
        File(temp.root, "Source").apply { mkdirs() }.resolve(name).apply { writeBytes(bytes) }

    /** The file as the page reads it: chunk after chunk from offset 0 until it has `size` bytes or a chunk comes back empty. */
    private fun readBack(opened: OpenedFiles, id: String, size: Long): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < size) {
            val data = Base64.getDecoder().decode(opened.chunk(id, out.size().toLong())!!["data"] as String)
            if (data.isEmpty()) break
            out.write(data)
        }
        return out.toByteArray()
    }

    @Test
    fun anOpenedFileIsListedAndReadsBackWhole() {
        val opened = store()
        // Over two chunks and a part, so the offsets carry on from each other.
        val bytes = ByteArray(OpenedFiles.CHUNK_BYTES * 2 + 1234) { (it * 31).toByte() }
        val original = source("Paper (draft).pdf", bytes)
        var arrived = 0
        opened.onArrival = { arrived += 1 }
        assertTrue(opened.take(original.inputStream(), "Paper (draft).pdf", "application/pdf"))
        assertEquals("the page is told", 1, arrived)
        val held = opened.files.single()
        assertEquals("Paper (draft).pdf", held.name)
        assertEquals(bytes.size.toLong(), held.size)
        assertEquals("application/pdf", held.type)
        assertTrue("a file opened from elsewhere is copied, not taken", original.isFile)
        @Suppress("UNCHECKED_CAST")
        val listed = opened.listing["files"] as List<Map<String, Any?>>
        assertEquals(listOf(mapOf("id" to held.id, "name" to held.name, "size" to bytes.size.toLong(), "type" to "application/pdf")), listed)
        assertArrayEquals(bytes, readBack(opened, held.id, held.size))
        assertEquals("a chunk is at most the chunk size", OpenedFiles.CHUNK_BYTES,
                     Base64.getDecoder().decode(opened.chunk(held.id, 0)!!["data"] as String).size)
        assertEquals("the end reads as an empty chunk", "", opened.chunk(held.id, held.size)!!["data"])
        assertNull("past the end", opened.chunk(held.id, held.size + 1))
        assertNull(opened.chunk(held.id, -1))
        assertNull(opened.chunk("no-such-file", 0))
    }

    @Test
    fun doneDropsTheCopyAndNothingElse() {
        val opened = store()
        val first = source("a.md", "# A".toByteArray())
        val second = source("b.note", "PK".toByteArray())
        opened.take(first.inputStream(), "a.md", "text/markdown")
        opened.take(second.inputStream(), "b.note", "")
        assertEquals("in the order they came", listOf("a.md", "b.note"), opened.files.map { it.name })
        assertEquals("a type the system does not know is none", "", opened.files[1].type)
        val copy = opened.files[0].file
        opened.done(opened.files[0].id)
        assertEquals(listOf("b.note"), opened.files.map { it.name })
        assertFalse("the copy goes", copy.exists())
        assertFalse("with its folder", copy.parentFile!!.exists())
        assertTrue("the original stays", first.isFile)
        opened.done("no-such-file")
        assertEquals(1, opened.files.size)
    }

    @Test
    fun aStreamThatCannotBeReadIsNotTaken() {
        val opened = store()
        var arrived = 0
        opened.onArrival = { arrived += 1 }
        var closed = false
        val failing = object : InputStream() {
            private var sent = 0
            override fun read(): Int = if (sent++ < 10) 7 else throw IOException("the provider went away")
            override fun close() { closed = true }
        }
        assertFalse(opened.take(failing, "gone.pdf", "application/pdf"))
        assertTrue("the stream is closed all the same", closed)
        assertTrue(opened.files.isEmpty())
        assertEquals(0, arrived)
        assertTrue("nothing is left of it", opened.folder.listFiles().isNullOrEmpty())
    }

    @Test
    fun aNameIsMadeSafeToKeep() {
        val opened = store()
        assertTrue(opened.take("x".byteInputStream(), "", ""))
        assertTrue(opened.take("x".byteInputStream(), "../../escape.pdf", "application/pdf"))
        assertTrue(opened.take("x".byteInputStream(), "..", ""))
        val long = "ü".repeat(200) + ".pdf"
        assertTrue(opened.take("x".byteInputStream(), long, "application/pdf"))
        val names = opened.files.map { it.name }
        assertEquals("File", names[0])
        assertEquals("..-..-escape.pdf", names[1])
        assertEquals("File", names[2])
        assertTrue(names[3].endsWith(".pdf"))
        assertTrue(names[3].toByteArray().size <= 255)
        for (entry in opened.files) assertEquals("each in a folder of its own", opened.folder, entry.file.parentFile!!.parentFile)
    }

    @Test
    fun anEarlierRunsCopiesGoAtLaunch() {
        val opened = store()
        opened.take("%PDF".byteInputStream(), "left.pdf", "application/pdf")
        assertTrue(opened.folder.isDirectory)
        val again = OpenedFiles(opened.folder)
        assertTrue(again.files.isEmpty())
        assertFalse(opened.folder.exists())
        assertTrue("a new file is taken into it afresh", again.take("%PDF".byteInputStream(), "new.pdf", "application/pdf"))
        assertEquals(listOf("new.pdf"), again.files.map { it.name })
    }

    @Test
    fun safeNamesKeepTheirExtension() {
        assertEquals("a-b.txt", safeFileName("a/b.txt", "File"))
        assertEquals("Download", safeFileName("   ", "Download"))
        val cut = safeFileName("x".repeat(300) + ".tar.gz", "File")
        assertEquals(255, cut.length)
        assertTrue(cut.endsWith(".gz"))
        val emoji = safeFileName("👻".repeat(100), "File")
        assertTrue(emoji.toByteArray().size <= 255)
        assertEquals("cut between characters, not inside one", 0, emoji.length % 2)
    }
}
