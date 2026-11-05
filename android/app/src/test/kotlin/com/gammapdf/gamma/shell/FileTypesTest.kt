package com.gammapdf.gamma.shell

import android.webkit.WebChromeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Types by file name (shell/FileTypes.kt) and the file chooser's picker
 * (Pick), with the accept values the web app's pickers use
 * (frontend/src/shared/lib/utils.js PAGE_FILE_ACCEPT, App.jsx, usePictures.js,
 * ChatDock.jsx; web-host.md §4.3).
 */
class FileTypesTest {
    private val table = mapOf(
        "pdf" to "application/pdf", "png" to "image/png", "zip" to "application/zip",
        "jpg" to "image/jpeg", "md" to "text/plain", "weird" to "",
    )
    private val lookup: (String) -> String? = { table[it] }

    @Test
    fun anExtensionIsWhatFollowsTheLastDot() {
        assertEquals("pdf", FileTypes.extension("Paper.PDF"))
        assertEquals("gz", FileTypes.extension("archive.tar.gz"))
        assertNull(FileTypes.extension(".bashrc"))
        assertNull(FileTypes.extension("README"))
        assertNull(FileTypes.extension("trailing."))
        assertNull(FileTypes.extension("dir.d/README"))
    }

    @Test
    fun markdownIsKnownByHand() {
        assertEquals("text/markdown", FileTypes.ofExtension("md", lookup))
        assertEquals("text/markdown", FileTypes.ofExtension(".markdown", lookup))
        assertEquals("text/markdown", FileTypes.ofExtension("MD") { null })
    }

    @Test
    fun otherExtensionsAskTheSystemsTable() {
        assertEquals("application/pdf", FileTypes.ofExtension(".PDF", lookup))
        assertNull(FileTypes.ofExtension("edn", lookup))
        assertNull(FileTypes.ofExtension("weird", lookup))
        assertNull(FileTypes.ofExtension("", lookup))
        assertNull(FileTypes.ofExtension(".", lookup))
    }

    @Test
    fun anOpenedFilesTypeIsItsExtensionsThenItsProviders() {
        assertEquals("application/pdf", FileTypes.opened("x.pdf", "application/octet-stream", lookup))
        assertEquals("text/markdown", FileTypes.opened("notes.md", "text/plain", lookup))
        // a Drive file named without its extension
        assertEquals("application/pdf", FileTypes.opened("Report", "application/pdf", lookup))
        assertEquals("text/plain", FileTypes.opened("Report", "Text/Plain; charset=utf-8", lookup))
        assertEquals("application/octet-stream", FileTypes.opened("book.goodnotes", "application/octet-stream", lookup))
    }

    @Test
    fun anOpenedFileWithNoTypeHasNone() {
        assertEquals("", FileTypes.opened("book.goodnotes", null, lookup))
        assertEquals("", FileTypes.opened("Folder", FileTypes.FOLDER, lookup))
        assertEquals("", FileTypes.opened("x", "nonsense", lookup))
    }

    @Test
    fun thePagesAcceptValuesBecomeTheirTypes() {
        // PAGE_FILE_ACCEPT
        assertEquals(listOf("application/pdf", "text/markdown"),
                     FileTypes.accepted(listOf(".pdf", ".md", ".markdown", "application/pdf", "text/markdown"), lookup))
        assertEquals(listOf("application/pdf"), FileTypes.accepted(listOf(".pdf", "application/pdf"), lookup))
        assertEquals(listOf("image/*"), FileTypes.accepted(listOf("image/*"), lookup))
        assertEquals(listOf("image/*", "application/pdf"), FileTypes.accepted(listOf("image/*", "application/pdf"), lookup))
        assertEquals(listOf("application/zip"), FileTypes.accepted(listOf(".zip", "application/zip"), lookup))
    }

    @Test
    fun aTokenWithoutATypeOpensThePickerToEverything() {
        assertEquals(FileTypes.ANY, FileTypes.accepted(listOf(".pdf", ".edn", ".md"), lookup))
        assertEquals(FileTypes.ANY, FileTypes.accepted(listOf(".note", ".ntb", ".goodnotes"), lookup))
        assertEquals(FileTypes.ANY, FileTypes.accepted(listOf("application/pdf", "*/*"), lookup))
        assertEquals(FileTypes.ANY, FileTypes.accepted(listOf("not a type/at all"), lookup))
    }

    @Test
    fun noAcceptOpensThePickerToEverything() {
        assertEquals(FileTypes.ANY, FileTypes.accepted(emptyList(), lookup))
        assertEquals(FileTypes.ANY, FileTypes.accepted(listOf(""), lookup))
        assertEquals(FileTypes.ANY, FileTypes.accepted(listOf(" ", ","), lookup))
    }

    @Test
    fun tokensAreTrimmedLowercasedAndSplit() {
        assertEquals(listOf("application/pdf"), FileTypes.accepted(listOf(" .PDF ", "Application/PDF"), lookup))
        assertEquals(listOf("image/*", "application/pdf"), FileTypes.accepted(listOf("image/*,application/pdf"), lookup))
    }

    @Test
    fun picturesAloneGoToThePhotoPicker() {
        assertEquals(Pick.Photos(false), Pick.of(listOf("image/*"), Pick.MODE_OPEN, lookup))
        assertEquals(Pick.Photos(true), Pick.of(listOf("image/*"), Pick.MODE_OPEN_MULTIPLE, lookup))
    }

    @Test
    fun anythingElseGoesToTheDocumentPicker() {
        assertEquals(Pick.Documents(listOf("image/*", "application/pdf"), false),
                     Pick.of(listOf("image/*", "application/pdf"), Pick.MODE_OPEN, lookup))
        assertEquals(Pick.Documents(listOf("image/png"), false), Pick.of(listOf(".png"), Pick.MODE_OPEN, lookup))
        assertEquals(Pick.Documents(FileTypes.ANY, true), Pick.of(listOf(""), Pick.MODE_OPEN_MULTIPLE, lookup))
    }

    @Test
    fun aFolderIsPickedAsSeveralFiles() {
        assertEquals(Pick.Documents(FileTypes.ANY, true), Pick.of(emptyList(), Pick.MODE_OPEN_FOLDER, lookup))
    }

    @Test
    fun aSaveOrAnUnknownModeOpensNothing() {
        assertNull(Pick.of(emptyList(), Pick.MODE_SAVE, lookup))
        assertNull(Pick.of(emptyList(), 7, lookup))
    }

    @Test
    fun theModesAreTheWebViews() {
        assertEquals(WebChromeClient.FileChooserParams.MODE_OPEN, Pick.MODE_OPEN)
        assertEquals(WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE, Pick.MODE_OPEN_MULTIPLE)
        assertEquals(WebChromeClient.FileChooserParams.MODE_SAVE, Pick.MODE_SAVE)
    }
}
