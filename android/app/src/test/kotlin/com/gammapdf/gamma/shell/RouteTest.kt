package com.gammapdf.gamma.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which navigations stay in the app, and when two addresses are one origin
 * (shell/Origin.kt), ported from ipad/GammaIPadTests/ShellTests.swift RouteTests.
 */
class RouteTest {
    private val server = Origin("https", "gamma.example.org")

    private fun route(url: String, mainFrame: Boolean = true) = Route.of(url, server, mainFrame)

    @Test
    fun theServersPagesStay() {
        assertEquals(Route.LOAD, route("https://gamma.example.org/p/abc"))
        assertEquals("the default port is the same origin", Route.LOAD, route("https://GAMMA.example.org:443/"))
        assertEquals(Route.LOAD, route("https://gamma.example.org"))
    }

    @Test
    fun theCloudStartIsTakenOver() {
        assertEquals(Route.CLOUD_SIGN_IN, route("https://gamma.example.org/api/auth/cloud/start?next=%2F"))
        assertEquals(Route.CLOUD_SIGN_IN, route("https://gamma.example.org/api/auth/cloud/start"))
        assertEquals("only the server's own start", Route.BROWSER, route("https://elsewhere.example/api/auth/cloud/start"))
        assertEquals(Route.LOAD, route("https://gamma.example.org/api/auth/cloud/start-other"))
    }

    @Test
    fun otherSitesLeaveTheWindowButEmbedsStay() {
        assertEquals(Route.BROWSER, route("https://arxiv.org/abs/1234"))
        assertEquals("another scheme is another origin", Route.BROWSER, route("http://gamma.example.org/"))
        assertEquals(Route.BROWSER, route("https://gamma.example.org:8443/"))
        assertEquals(Route.LOAD, route("https://www.youtube.com/embed/x", mainFrame = false))
        assertEquals("with no server shown, every site is another", Route.BROWSER, Route.of("https://gamma.example.org/", null, true))
    }

    @Test
    fun otherAppsLinksGoToTheSystem() {
        assertEquals(Route.SYSTEM, route("mailto:someone@example.org"))
        assertEquals(Route.SYSTEM, route("tel:+15551234"))
        assertEquals(Route.SYSTEM, route("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(Route.LOAD, route("about:blank"))
        assertEquals(Route.LOAD, route("blob:https://gamma.example.org/1b2c"))
        assertEquals(Route.LOAD, route("data:text/plain,hi"))
    }

    @Test
    fun originsPrintWithoutTheDefaultPort() {
        assertEquals("https://gamma.example.org", Origin.of("https://gamma.example.org/x")?.string)
        assertEquals("http://192.168.1.5:9001", Origin.of("http://192.168.1.5:9001")?.string)
        assertEquals(Origin("http", "[::1]", 9001), Origin("http", "::1", 9001))
        assertEquals("http://[::1]:9001", Origin.of("http://[::1]:9001/p")?.string)
        assertEquals(Origin("http", "::1", 9001), Origin.of("http://[::1]:9001/p"))
    }

    @Test
    fun aListenersOriginComparesWithThePages() {
        // WebMessageListener's sourceOrigin is a Uri with no port for the default (-1), and no path.
        assertEquals(Origin.of("http://127.0.0.1:50123"), Origin.of("http://127.0.0.1:50123/p/abc?ws=w1#b"))
        assertEquals(Origin("https", "gamma.example.org", -1), server)
        assertEquals(Origin("https", "gamma.example.org", 0), server)
        assertEquals(Origin.of("https://gamma.example.org"), Origin.of("https://user:pw@Gamma.Example.org:443/"))
        assertNotEquals(Origin.of("http://127.0.0.1:50123"), Origin.of("http://127.0.0.1:50124"))
        assertNotEquals(Origin.of("http://localhost:50123"), Origin.of("http://127.0.0.1:50123"))
        assertNull("no host", Origin.of("about:blank"))
        assertNull(Origin.of("file:///sdcard/a.pdf"))
        assertNull(Origin.of("https://"))
        assertNull(Origin.of("gamma.example.org"))
        assertNull(Origin.of(null))
    }

    @Test
    fun aUrlsPartsAreReadAsABrowserWrites() {
        val parts = UrlParts.parse("http://my_nas.local:9001/p/a%20b?ws=w%2F2&x&ws=w3&next=%2Fa%3Fb+c#top")!!
        assertEquals("http", parts.scheme)
        assertEquals("a name a browser takes", "my_nas.local", parts.host)
        assertEquals(9001, parts.port)
        assertEquals("/p/a b", parts.path)
        assertEquals("the first of its name", "w/2", parts.queryValue("ws"))
        assertNull("an item without a value", parts.queryValue("x"))
        assertEquals("a + is left as it is", "/a?b+c", parts.queryValue("next"))
        assertNull("not in the query", parts.queryValue("top"))
        assertNull("a port is a number", UrlParts.parse("http://nas.local:port/"))
        assertNull("of 16 bits", UrlParts.parse("http://nas.local:65536/"))
        assertNull("no space in a host", UrlParts.parse("https://my server/"))
        assertNull(UrlParts.parse("http://[::1/"))
        assertEquals("ü", UrlParts.percentDecode("%C3%BC"))
        assertEquals("a % that starts no escape stays", "100%", UrlParts.percentDecode("100%"))
    }
}
