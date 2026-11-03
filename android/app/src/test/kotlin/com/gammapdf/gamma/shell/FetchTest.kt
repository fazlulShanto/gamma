package com.gammapdf.gamma.shell

import java.io.IOException
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's own requests (shell/Fetch.kt): a save's GET with each hop's
 * cookies and the WebView's user agent, against stand-in servers, and what
 * a save may reach (SaveHops).
 */
class FetchTest {
    /** No server shown and nothing on the device: the stand-in servers are on loopback. */
    private val anywhere = SaveHops(null) { false }

    @Test
    fun onlyTheServersHopsCarryItsCookies() {
        FakeHttp { FakeHttp.Response(200, listOf("Content-Disposition" to "attachment; filename=\"a.pdf\""), "pdf!") }.use { files ->
            FakeHttp { FakeHttp.Response(302, listOf("Location" to files.origin + "/a.pdf")) }.use { server ->
                val asked = Collections.synchronizedList(ArrayList<String>())
                // The store would give any address a cookie: the hops decide who gets one.
                val cookies = { url: String ->
                    asked += url
                    "gamma_session=s1"
                }
                val hops = SaveHops(Origin.of(server.origin)) { false }
                val connection = Fetch.get(server.origin + "/api/jobs/7/download", "GammaTest/1", hops, cookies)
                try {
                    assertEquals("pdf!", connection.inputStream.readBytes().toString(Charsets.UTF_8))
                    assertEquals(files.origin + "/a.pdf", connection.url.toString())
                    assertEquals("attachment; filename=\"a.pdf\"", connection.getHeaderField("Content-Disposition"))
                } finally {
                    connection.disconnect()
                }
                assertEquals("another site's cookies are not even read", listOf(server.origin + "/api/jobs/7/download"), asked)
                assertEquals("gamma_session=s1", server.requests.single().header("Cookie"))
                assertNull("the server's session stays with the server", files.requests.single().header("Cookie"))
                assertEquals("GammaTest/1", server.requests.single().header("User-Agent"))
                assertEquals("GammaTest/1", files.requests.single().header("User-Agent"))
            }
        }
    }

    @Test
    fun aRelativeRedirectStaysOnTheServer() {
        FakeHttp { request ->
            if (request.target == "/old") FakeHttp.Response(303, listOf("Location" to "new?x=1")) else FakeHttp.Response(200, body = request.target)
        }.use { server ->
            val connection = Fetch.get(server.origin + "/old", null, anywhere) { null }
            try {
                assertEquals("/new?x=1", connection.inputStream.readBytes().toString(Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun aRefusalSaysWhatTheServerSaid() {
        FakeHttp { FakeHttp.Response(404, body = """{"detail":"No such job."}""") }.use { server ->
            assertEquals("No such job.", assertThrows(IOException::class.java) { Fetch.get(server.origin + "/api/jobs/9/download", null, anywhere) { null } }.message)
        }
        FakeHttp { FakeHttp.Response(500, body = "Internal Server Error") }.use { server ->
            assertEquals("HTTP 500", assertThrows(IOException::class.java) { Fetch.get(server.origin + "/x", null, anywhere) { null } }.message)
        }
    }

    @Test
    fun redirectsEndSomewhere() {
        FakeHttp { FakeHttp.Response(302, listOf("Location" to "/again")) }.use { server ->
            assertEquals("Too many redirects", assertThrows(IOException::class.java) { Fetch.get(server.origin + "/", null, anywhere) { null } }.message)
            assertEquals(Fetch.MAX_REDIRECTS + 1, server.requests.size)
        }
        FakeHttp { FakeHttp.Response(302, listOf("Location" to "ftp://files.example/x")) }.use { server ->
            assertEquals("HTTP 302", assertThrows(IOException::class.java) { Fetch.get(server.origin + "/", null, anywhere) { null } }.message)
        }
    }

    @Test
    fun onlyWebAddressesAreFetched() {
        assertThrows(IOException::class.java) { Fetch.get("file:///data/data/com.gammapdf.gamma/shared_prefs/gamma.xml", null, anywhere) { null } }
        assertThrows(IOException::class.java) { Fetch.get("blob:http://127.0.0.1:8123/5f1c", null, anywhere) { null } }
    }

    @Test
    fun aRedirectResolvesAgainstWhereItCameFrom() {
        assertEquals("http://h/c", Fetch.redirect("http://h/a/b", "../c"))
        assertEquals("https://other.example/x", Fetch.redirect("http://h/a", "https://other.example/x"))
        assertNull(Fetch.redirect("http://h/a", "mailto:someone@example.org"))
        assertNull(Fetch.redirect("http://h/a", null))
        assertNull(Fetch.redirect("http://h/a", " "))
    }

    @Test
    fun aProblemIsTheServersDetailOrTheStatus() {
        assertEquals("Not signed in.", Fetch.problem(401, """{"detail":"Not signed in."}"""))
        assertEquals("HTTP 422", Fetch.problem(422, """{"detail":[{"loc":["body"],"msg":"field required"}]}"""))
        assertEquals("HTTP 502", Fetch.problem(502, ""))
        assertEquals("HTTP 400", Fetch.problem(400, """{"detail":" "}"""))
    }

    @Test
    fun aSaveReachesNothingOnTheDeviceButTheServerShown() {
        val looked = ArrayList<String>()
        val device = setOf("127.0.0.1", "::1", "localhost")
        fun hops(server: String?) = SaveHops(Origin.of(server)) { host -> looked += host; host in device }

        val remote = hops("https://gamma.example.org")
        assertNull(remote.refusal("https://gamma.example.org/api/jobs/7/download"))
        assertEquals("the server's own hops are not looked up", emptyList<String>(), looked)
        assertNull("another site: a note's picture, a storage redirect", remote.refusal("https://files.example/a.png"))
        assertEquals(SaveHops.ON_DEVICE, remote.refusal("http://127.0.0.1:50123/api/admin/backups/b/download"))
        assertEquals(SaveHops.ON_DEVICE, remote.refusal("http://localhost:50123/"))
        assertEquals(SaveHops.ON_DEVICE, remote.refusal("http://[::1]:50123/"))

        val local = hops("http://127.0.0.1:50123")
        assertNull(local.refusal("http://127.0.0.1:50123/api/uploads/a.pdf"))
        assertEquals("another port on the device", SaveHops.ON_DEVICE, local.refusal("http://127.0.0.1:50999/"))
        assertEquals("the same server by another name", SaveHops.ON_DEVICE, local.refusal("http://localhost:50123/"))
        assertEquals("no server shown", SaveHops.ON_DEVICE, hops(null).refusal("http://127.0.0.1:50123/"))
    }

    @Test
    fun aSaveAsksOnlyAddressesEveryParserReadsAlike() {
        val remote = SaveHops(Origin.of("https://gamma.example.org")) { false }
        assertNull(remote.refusal("HTTPS://Gamma.Example.org:443/x?y=1#z"))
        assertNull(remote.refusal("http://nas_box:8080"))
        assertNull(remote.refusal("http://[fd00::1]:8080/x"))
        // The connection and the cookie store read a backslash as a slash: the host would be 127.0.0.1.
        assertEquals(SaveHops.NOT_PLAIN, remote.refusal("https://127.0.0.1:50123\\@gamma.example.org/"))
        assertEquals(SaveHops.NOT_PLAIN, remote.refusal("https://user@gamma.example.org/"))
        assertEquals(SaveHops.NOT_PLAIN, remote.refusal("http://%31%32%37.0.0.1/"))
        assertEquals(SaveHops.NOT_PLAIN, remote.refusal("http://\uFF11\uFF12\uFF17.0.0.1/"))
        assertEquals(SaveHops.NOT_PLAIN, remote.refusal("http://h:99999/"))
        assertEquals(SaveHops.NOT_PLAIN, remote.refusal("ftp://gamma.example.org/"))
    }

    @Test
    fun onlyTheServerShownGetsTheCookies() {
        val remote = SaveHops(Origin.of("https://gamma.example.org")) { false }
        assertTrue(remote.sendsCookies("https://gamma.example.org:443/api/x"))
        assertFalse(remote.sendsCookies("http://gamma.example.org/api/x"))
        assertFalse(remote.sendsCookies("https://files.example/a.png"))
        assertFalse(SaveHops(null) { false }.sendsCookies("https://gamma.example.org/"))
    }

    @Test
    fun theDeviceIsWhatItsAddressesResolveTo() {
        for (host in listOf("127.0.0.1", "127.8.9.10", "::1", "0.0.0.0", "::", "::ffff:127.0.0.1", "localhost")) {
            assertTrue(host, SaveHops.isOnDevice(host))
        }
        for (host in listOf("192.168.1.20", "10.0.2.2", "93.184.216.34", "fd00::1", "::ffff:10.0.0.1")) {
            assertFalse(host, SaveHops.isOnDevice(host))
        }
    }

    @Test
    fun aRedirectToTheDeviceIsNotFollowed() {
        FakeHttp { FakeHttp.Response(200, body = "local!") }.use { device ->
            FakeHttp { FakeHttp.Response(302, listOf("Location" to device.origin + "/api/admin/backups/b/download")) }.use { server ->
                val asked = ArrayList<String>()
                val refused = assertThrows(IOException::class.java) {
                    Fetch.get(server.origin + "/api/uploads/a.png", null, SaveHops(Origin.of(server.origin))) { hop ->
                        asked += hop
                        "gamma_session=s1"
                    }
                }
                assertEquals(SaveHops.ON_DEVICE, refused.message)
                assertEquals("gamma_session=s1", server.requests.single().header("Cookie"))
                assertEquals(listOf(server.origin + "/api/uploads/a.png"), asked)
                assertTrue("the device's other port is never asked", device.requests.isEmpty())
            }
        }
    }
}
