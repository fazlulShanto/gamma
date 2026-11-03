package com.gammapdf.gamma.shell

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The files the page hands the shell to save (shell/Saves.kt): put together
 * from chunks in order, complete only at the size they were begun with, and
 * gone when cancelled. The page's half is frontend/tests/saveFile.test.mjs.
 */
class SavesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun saves() = Saves(File(temp.root, "Saves"))

    /** The web's chunks: 2,097,150 raw bytes each, a multiple of 3 so each base64s on its own. */
    private val chunk = 2_097_150

    @Test
    fun aFileIsPutTogetherFromItsChunks() {
        val saves = saves()
        val bytes = ByteArray(5 * 1024 * 1024) { (it * 7).toByte() }
        val id = saves.begin("Paper (draft).pdf", "application/pdf", bytes.size.toLong())!!
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunk, bytes.size)
            assertTrue(saves.append(id, offset.toLong(), bytes.copyOfRange(offset, end)))
            offset = end
        }
        assertEquals("application/pdf", saves.mime(id))
        val file = saves.finish(id, cancel = false)!!
        assertEquals("Paper (draft).pdf", file.name)
        assertArrayEquals(bytes, file.readBytes())
        assertNull("finished once", saves.finish(id, cancel = false))
        assertNull(saves.mime(id))
        saves.release(file)
        assertFalse("presented, its folder goes", file.parentFile!!.exists())
    }

    @Test
    fun theBytesComeInOrder() {
        val saves = saves()
        val id = saves.begin("a.txt", "text/plain", 6)!!
        assertFalse("an unknown file", saves.append("no-such-save", 0, byteArrayOf(1)))
        assertFalse("a gap", saves.append(id, 3, byteArrayOf(1, 2, 3)))
        assertTrue(saves.append(id, 0, byteArrayOf(1, 2, 3)))
        assertFalse("the same chunk again", saves.append(id, 0, byteArrayOf(1, 2, 3)))
        assertFalse("past the size it was begun with", saves.append(id, 3, byteArrayOf(4, 5, 6, 7)))
        assertTrue(saves.append(id, 3, byteArrayOf(4, 5, 6)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), saves.finish(id, cancel = false)!!.readBytes())
    }

    @Test
    fun aShortOrCancelledFileIsDropped() {
        val saves = saves()
        val short = saves.begin("a.txt", "text/plain", 6)!!
        saves.append(short, 0, byteArrayOf(1, 2, 3))
        assertNull("not the size it was begun with", saves.finish(short, cancel = false))
        val cancelled = saves.begin("b.txt", "text/plain", 3)!!
        saves.append(cancelled, 0, byteArrayOf(1, 2, 3))
        assertNull(saves.finish(cancelled, cancel = true))
        assertNull(saves.finish("no-such-save", cancel = false))
        assertTrue("nothing is left of either", saves.folder.listFiles().isNullOrEmpty())
    }

    @Test
    fun anEmptyFileIsAFile() {
        val saves = saves()
        val id = saves.begin("empty.md", "text/markdown", 0)!!
        assertEquals(0L, saves.finish(id, cancel = false)!!.length())
    }

    @Test
    fun whatCannotBeSavedIsRefused() {
        val saves = saves()
        assertNull(saves.begin("a.txt", "text/plain", -1))
        assertNull("more than the disk has free", saves.begin("huge.zip", "application/zip", Long.MAX_VALUE))
        assertTrue(saves.folder.listFiles().isNullOrEmpty())
    }

    @Test
    fun theNameAndTypeAreKeptSafely() {
        val saves = saves()
        val nameless = saves.begin("", "", 0)!!
        assertEquals("", saves.mime(nameless))
        assertEquals("Download", saves.finish(nameless, cancel = false)!!.name)
        val svg = saves.begin("../handwriting.svg", "image/svg+xml;charset=utf-8", 0)!!
        assertEquals("the type without its parameters", "image/svg+xml", saves.mime(svg))
        val file = saves.finish(svg, cancel = false)!!
        assertEquals("..-handwriting.svg", file.name)
        assertEquals(saves.folder, file.parentFile!!.parentFile)
    }

    @Test
    fun aPageGoneTakesItsSavesWithIt() {
        val saves = saves()
        val done = saves.begin("kept.txt", "text/plain", 1)!!
        saves.append(done, 0, byteArrayOf(1))
        val kept = saves.finish(done, cancel = false)!!
        val pending = saves.begin("half.txt", "text/plain", 2)!!
        saves.append(pending, 0, byteArrayOf(1))
        val half = File(File(saves.folder, pending), "half.txt")
        assertTrue(half.isFile)
        val delete = saves.cancelAll()
        assertFalse("dropped at once", saves.append(pending, 1, byteArrayOf(2)))
        assertNull(saves.finish(pending, cancel = false))
        val next = saves.begin("next.txt", "text/plain", 1)!!
        delete()
        assertFalse("what was written goes when the step runs", half.parentFile!!.exists())
        assertTrue("a finished file is the presenter's until it lets it go", kept.isFile)
        assertTrue("a save begun after the page went is the next page's", saves.append(next, 0, byteArrayOf(3)))
        assertNotNull(saves.finish(next, cancel = false))
    }

    @Test
    fun anEarlierRunsFilesGoAtLaunch() {
        val saves = saves()
        val id = saves.begin("left.txt", "text/plain", 0)!!
        assertNotNull(saves.finish(id, cancel = false))
        Saves(saves.folder)
        assertFalse(saves.folder.exists())
    }
}
