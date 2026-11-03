package com.gammapdf.gamma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the address field accepts (app/ServerAddress.kt), ported from ipad/GammaIPadTests/ShellTests.swift. */
class ServerAddressTest {
    @Test
    fun aBareHostMeansHttps() {
        assertEquals("https://gamma.example.org", ServerAddress.parse("  gamma.example.org \n"))
        assertEquals("https://nas.local:9001", ServerAddress.parse("nas.local:9001"))
    }

    @Test
    fun aLinkKeepsOnlyItsOrigin() {
        assertEquals("http://nas.local:8080", ServerAddress.parse("http://NAS.local:8080/p/abc?x=1#top"))
        assertEquals("https://gamma.example.org", ServerAddress.parse("HTTPS://user:pw@Gamma.Example.org/p?ws=w1"))
        assertEquals("http://192.168.1.5:9001", ServerAddress.parse("http://192.168.1.5:9001/"))
        assertEquals("http://[fe80::1]:9001", ServerAddress.parse("http://[FE80::1]:9001/x"))
        assertEquals("a name a browser takes", "http://my_nas.local:9001", ServerAddress.parse("http://my_nas.local:9001"))
    }

    @Test
    fun aNameInAnotherScriptIsWrittenAsTheWebViewWritesIt() {
        assertEquals("https://xn--bcher-kva.example", ServerAddress.parse("Bücher.example"))
    }

    @Test
    fun otherSchemesAndBlanksAreRefused() {
        assertNull(ServerAddress.parse("ftp://gamma.example.org"))
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse(" \n"))
        assertNull(ServerAddress.parse("https://"))
        assertNull(ServerAddress.parse("https://my server"))
        assertNull(ServerAddress.parse("http://nas.local:99999"))
    }
}
