package com.gammapdf.gamma.shell

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The Gamma Cloud sign-in's steps that need no device (shell/CloudFlow.kt):
 * the loopback's reading of the browser's request, and app-start and
 * app-claim sent to a stand-in server as backend/gamma/routers/cloud_auth.py
 * answers them.
 */
class CloudFlowTest {
    private val request = "GET /cloud-signin?result=r1+r2 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"

    /** A stream that hands out at most [piece] bytes a read and fails if read past [end]. */
    private class Trickle(text: String, private val piece: Int, private val end: Int = Int.MAX_VALUE) : InputStream() {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        private var at = 0

        override fun read(): Int {
            check(at < end) { "read past the request line" }
            return if (at < bytes.size) bytes[at++].toInt() and 0xff else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(len, piece, bytes.size - at, end - at)
            check(n > 0) { "read past the request line" }
            System.arraycopy(bytes, at, b, off, n)
            at += n
            return n
        }
    }

    @Test
    fun theHeadIsReadToItsFirstLineAndNoFurther() {
        val line = "GET /cloud-signin?result=r1+r2 HTTP/1.1\r\n"
        assertEquals(line, CloudFlow.requestHead(Trickle(request, piece = 3, end = line.length)))
        assertEquals(CloudAnswer.Result("r1 r2"), CloudWire.loopbackAnswer(CloudFlow.requestHead(Trickle(request, piece = 1))))
    }

    @Test
    fun aHeadWithoutItsLineEndIsWhatCame() {
        assertEquals("GET /cloud-signin?error=x", CloudFlow.requestHead(ByteArrayInputStream("GET /cloud-signin?error=x".toByteArray())))
        assertEquals("", CloudFlow.requestHead(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aHeadStopsAtSixteenKibibytes() {
        val endless = object : InputStream() {
            override fun read(): Int = 'a'.code
        }
        assertEquals(16 * 1024, CloudFlow.requestHead(endless).length)
        assertEquals(10, CloudFlow.requestHead(endless, limit = 10).length)
    }

    @Test
    fun theRestOfTheHeadIsReadToItsEmptyLine() {
        val input = ByteArrayInputStream(request.toByteArray())
        CloudFlow.requestHead(input)
        CloudFlow.skipHead(input, CloudFlow.HEAD_LIMIT)
        assertEquals("the request is read whole, so the socket closes without a reset", -1, input.read())

        val pipelined = ByteArrayInputStream("GET / HTTP/1.1\r\n\r\nNEXT".toByteArray())
        CloudFlow.requestHead(pipelined)
        CloudFlow.skipHead(pipelined, CloudFlow.HEAD_LIMIT)
        assertEquals("NEXT", pipelined.readBytes().toString(Charsets.UTF_8))

        val endless = object : InputStream() {
            var count = 0
            override fun read(): Int = 'a'.code.also { count += 1 }
        }
        CloudFlow.skipHead(endless, 100)
        assertEquals(100, endless.count)
    }

    @Test
    fun theClaimLeadsWhereTheServerRedirects() {
        val origin = Origin("http", "127.0.0.1", 8123)
        assertEquals("http://127.0.0.1:8123/", CloudFlow.landing(origin, 303, "/", ""))
        assertEquals("http://127.0.0.1:8123/settings?tab=account", CloudFlow.landing(origin, 303, "/settings?tab=account", ""))
        assertEquals("http://127.0.0.1:8123/?cloud_error=The+sign-in+expired.", CloudFlow.landing(origin, 302, "/?cloud_error=The+sign-in+expired.", ""))
        assertEquals("http://127.0.0.1:8123/notes", CloudFlow.landing(origin, 303, "http://127.0.0.1:8123/notes", ""))
    }

    @Test
    fun aRedirectOffTheServerLandsOnItsHome() {
        val origin = Origin("https", "gamma.example.org")
        assertEquals("https://gamma.example.org/", CloudFlow.landing(origin, 303, "https://evil.example/x", ""))
        assertEquals("https://gamma.example.org/", CloudFlow.landing(origin, 303, "//evil.example/x", ""))
        assertEquals("https://gamma.example.org/", CloudFlow.landing(origin, 303, "http://gamma.example.org/x", ""))
    }

    @Test
    fun anythingButARedirectIsRefusedWithTheServersWords() {
        val origin = Origin("http", "127.0.0.1", 8123)
        val refused = assertThrows(Refusal::class.java) {
            CloudFlow.landing(origin, 403, null, """{"detail":"Only the Gamma desktop app redeems a sign-in here."}""")
        }
        assertEquals("Only the Gamma desktop app redeems a sign-in here.", refused.message)
        assertEquals("HTTP 500", assertThrows(Refusal::class.java) { CloudFlow.landing(origin, 500, null, "Internal Server Error") }.message)
        assertEquals("HTTP 302", assertThrows(Refusal::class.java) { CloudFlow.landing(origin, 302, " ", "") }.message)
    }

    @Test
    fun setCookiesAreFoundInAnyCase() {
        val fields = linkedMapOf<String?, List<String>>(
            null to listOf("HTTP/1.1 303 See Other"),
            "set-cookie" to listOf("gamma_session=a; Path=/; HttpOnly; SameSite=Lax"),
            "Location" to listOf("/"),
            "Set-Cookie" to listOf("other=b"),
        )
        assertEquals(listOf("gamma_session=a; Path=/; HttpOnly; SameSite=Lax", "other=b"), CloudFlow.setCookies(fields))
        assertEquals(emptyList<String>(), CloudFlow.setCookies(emptyMap()))
    }

    @Test
    fun appStartCarriesThePagesSessionAndReadsTheAuthorizeUrl() {
        FakeHttp { FakeHttp.Response(200, listOf("Content-Type" to "application/json"), """{"url":"https://account.gammapdf.com/authorize?x=1"}""") }.use { server ->
            val origin = Origin.of(server.origin)!!
            val body = CloudWire.AppStart("/", true, "http://127.0.0.1:50123/cloud-signin", "c")
            val answer = CloudFlow.appStart(origin, body, "gamma_session=s1")
            assertEquals("https://account.gammapdf.com/authorize?x=1", CloudWire.authorizeUrl(answer.status, answer.text))
            val sent = server.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("/api/auth/cloud/app-start", sent.target)
            assertEquals("application/json", sent.header("Content-Type"))
            assertEquals("gamma_session=s1", sent.header("Cookie"))
            assertEquals(body.json, sent.body)
        }
    }

    @Test
    fun appStartOnAnOlderServerOrARefusal() {
        FakeHttp { FakeHttp.Response(404, body = """{"detail":"Not Found"}""") }.use { server ->
            val answer = CloudFlow.appStart(Origin.of(server.origin)!!, CloudWire.AppStart("/", false, "r", "c"), null)
            assertNull(CloudWire.authorizeUrl(answer.status, answer.text))
            assertNull(server.requests.single().header("Cookie"))
        }
        FakeHttp { FakeHttp.Response(403, body = """{"detail":"Sign in with a password first to link a Gamma Cloud account."}""") }.use { server ->
            val answer = CloudFlow.appStart(Origin.of(server.origin)!!, CloudWire.AppStart("/", true, "r", "c"), null)
            val refused = assertThrows(Refusal::class.java) { CloudWire.authorizeUrl(answer.status, answer.text) }
            assertEquals("Sign in with a password first to link a Gamma Cloud account.", refused.message)
        }
    }

    @Test
    fun theClaimIsSentAsTheAppsAndItsRedirectIsNotFollowed() {
        FakeHttp { request ->
            if (request.target == "/api/auth/cloud/app-claim") {
                FakeHttp.Response(303, listOf("Location" to "/notes?ws=w1", "Cache-Control" to "no-store",
                                              "Set-Cookie" to "gamma_session=new; HttpOnly; Path=/; SameSite=Lax",
                                              "Set-Cookie" to "gamma_hint=1; Path=/"))
            } else {
                FakeHttp.Response(404)
            }
        }.use { server ->
            val origin = Origin.of(server.origin)!!
            val claimed = CloudFlow.claim(origin, CloudWire.claimRequest(origin, "r 1+2", "v"), "gamma_session=old")
            assertEquals(server.origin + "/notes?ws=w1", claimed.landing)
            assertEquals(listOf("gamma_session=new; HttpOnly; Path=/; SameSite=Lax", "gamma_hint=1; Path=/"), claimed.cookies)
            val sent = server.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("claim", sent.header("X-Gamma-Desktop"))
            assertEquals("application/x-www-form-urlencoded", sent.header("Content-Type"))
            assertEquals("gamma_session=old", sent.header("Cookie"))
            assertEquals("result=r%201%2B2&verifier=v", sent.body)
        }
    }

    @Test
    fun aRefusedClaimSaysWhy() {
        FakeHttp { FakeHttp.Response(403, body = """{"detail":"Only the Gamma desktop app redeems a sign-in here."}""") }.use { server ->
            val origin = Origin.of(server.origin)!!
            val refused = assertThrows(Refusal::class.java) { CloudFlow.claim(origin, CloudWire.claimRequest(origin, "r", "v"), null) }
            assertEquals("Only the Gamma desktop app redeems a sign-in here.", refused.message)
        }
    }
}
