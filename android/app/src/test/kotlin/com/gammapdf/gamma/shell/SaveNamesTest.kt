package com.gammapdf.gamma.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The names and types of saved files (shell/SaveNames.kt): Content-Disposition
 * as the server writes it (Starlette's FileResponse, routers/jobs.py) and
 * as the web reads it (frontend/src/shared/lib/saveFile.js dispositionName),
 * and the brief's precedence for saveUrl.
 */
class SaveNamesTest {
    private val table = mapOf(
        "pdf" to "application/pdf", "png" to "image/png", "zip" to "application/zip",
        "svg" to "image/svg+xml", "txt" to "text/plain", "csv" to "text/csv",
    )
    private val lookup: (String) -> String? = { table[it] }

    @Test
    fun thePagesNameWinsOverTheServers() {
        // An upload is served under its hash; the page knows the person's name for it.
        assertEquals("data.csv", SaveNames.choose("data.csv", "attachment; filename=\"a1b2c3.csv\"", "http://127.0.0.1:8123/api/uploads/a1b2c3.csv?ws=w1"))
    }

    @Test
    fun withoutOneTheResponseNamesTheFile() {
        assertEquals("Paper.pdf", SaveNames.choose("", "attachment; filename=\"Paper.pdf\"", "http://h/api/jobs/7/download"))
        assertEquals("Paper.pdf", SaveNames.choose("  ", "attachment; filename=\"Paper.pdf\"", "http://h/api/jobs/7/download"))
    }

    @Test
    fun thenTheLastSegmentOfTheAddress() {
        assertEquals("ab cd.png", SaveNames.choose("", null, "http://127.0.0.1:8123/api/uploads/ab%20cd.png?ws=w1#x"))
        assertEquals("download", SaveNames.choose("", "inline", "http://h/api/jobs/7/download"))
    }

    @Test
    fun thenDownload() {
        assertEquals("Download", SaveNames.choose("", null, "http://h/"))
        assertEquals("Download", SaveNames.choose("", null, "not a url"))
    }

    @Test
    fun theExtendedNameWinsAndIsDecoded() {
        assertEquals("€ rates.pdf", SaveNames.fileName("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E2%82%AC%20rates.pdf"))
        // Starlette writes the charset in lowercase.
        assertEquals("Naïve notes.md", SaveNames.fileName("attachment; filename*=utf-8''Na%C3%AFve%20notes.md"))
        assertEquals("£ rates.txt", SaveNames.fileName("attachment; filename*=iso-8859-1'en'%A3%20rates.txt"))
    }

    @Test
    fun anExtendedNameThatDoesNotReadGivesWayToThePlainOne() {
        assertEquals("plain.pdf", SaveNames.fileName("attachment; filename*=UTF-8''%E2%82; filename=\"plain.pdf\""))
        assertEquals("plain.pdf", SaveNames.fileName("attachment; filename*=UTF-8''%ZZ.pdf; filename=\"plain.pdf\""))
        assertEquals("plain.pdf", SaveNames.fileName("attachment; filename*=koi8-r''x.pdf; filename=plain.pdf"))
        assertEquals("plain.pdf", SaveNames.fileName("attachment; filename*=x.pdf; filename=plain.pdf"))
    }

    @Test
    fun aQuotedNameMayHoldSemicolonsAndEscapes() {
        assertEquals("a \"b\"; c.txt", SaveNames.fileName("attachment; filename=\"a \\\"b\\\"; c.txt\"; size=3"))
    }

    @Test
    fun aTokenAndAnyCaseRead() {
        assertEquals("report.pdf", SaveNames.fileName("attachment; filename=report.pdf"))
        assertEquals("x.pdf", SaveNames.fileName("Attachment; FILENAME=\"x.pdf\""))
        assertEquals("x.pdf", SaveNames.fileName("attachment ;  filename = \"x.pdf\" "))
    }

    @Test
    fun onlyTheLastPartOfAPathIsKept() {
        assertEquals("passwd", SaveNames.fileName("attachment; filename=\"../../etc/passwd\""))
        assertEquals("c.txt", SaveNames.fileName("attachment; filename*=UTF-8''a%2Fb%5Cc.txt"))
        assertNull(SaveNames.fileName("attachment; filename=\"..\""))
        assertNull(SaveNames.fileName("attachment; filename=\"dir/\""))
    }

    @Test
    fun aHeaderWithoutANameGivesNone() {
        assertNull(SaveNames.fileName(null))
        assertNull(SaveNames.fileName(""))
        assertNull(SaveNames.fileName("inline"))
        assertNull(SaveNames.fileName("attachment; filename="))
        assertNull(SaveNames.fileName("attachment; name=\"x.pdf\""))
    }

    @Test
    fun aNameWithAKnownExtensionIsFiledUnderItsType() {
        assertEquals("application/pdf", SaveNames.mediaMime("report.pdf", "application/octet-stream", lookup))
        assertEquals("text/csv", SaveNames.mediaMime("data.csv", "text/plain", lookup))
        assertEquals("text/markdown", SaveNames.mediaMime("notes.md", "", lookup))
    }

    @Test
    fun anUnknownExtensionIsFiledUnderNoTypeSoTheNameStays() {
        assertNull(SaveNames.mediaMime("export.edn", "application/edn", lookup))
        assertNull(SaveNames.mediaMime("book.goodnotes", "", lookup))
    }

    @Test
    fun aNameWithoutAnExtensionTakesTheTypeItCameWith() {
        assertEquals("application/pdf", SaveNames.mediaMime("download", "application/pdf; charset=binary", lookup))
        assertEquals("image/svg+xml", SaveNames.mediaMime("handwriting", "Image/SVG+xml;charset=utf-8", lookup))
        assertNull(SaveNames.mediaMime("download", "application/octet-stream", lookup))
        assertNull(SaveNames.mediaMime("download", "", lookup))
        assertNull(SaveNames.mediaMime("download", null, lookup))
    }
}
