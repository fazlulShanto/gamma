package com.gammapdf.gamma.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gamma Cloud sign-in's contract with the server (shell/CloudWire.kt,
 * Pkce), ported from ipad/GammaIPadTests/CloudSignInTests.swift: the same
 * requests the desktop app makes (desktop/lib/cloudSignIn.js), which
 * backend/gamma/routers/cloud_auth.py answers. The browser and the loopback
 * listener need a device.
 */
class CloudWireTest {
    @Test
    fun theChallengeIsS256OfTheVerifier() {
        // RFC 7636 appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").challenge)
    }

    @Test
    fun aVerifierIsFortyThreeUrlSafeCharactersAndNewEachTime() {
        val a = Pkce()
        val b = Pkce()
        assertEquals(43, a.verifier.length)
        assertEquals("the server's challenge check wants 43 characters", 43, a.challenge.length)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(a.verifier))
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(a.challenge))
        assertNotEquals(a.verifier, b.verifier)
    }

    @Test
    fun appStartCarriesWhatThePageAskedFor() {
        val body = CloudWire.AppStart.from("https://gamma.example.org/api/auth/cloud/start?link=1&next=%2Fsettings%3Ftab%3Daccount",
                                           "http://127.0.0.1:50123/cloud-signin", "c")
        assertEquals("/settings?tab=account", body.next)
        assertTrue(body.link)
        assertEquals("""{"challenge":"c","link":true,"next":"/settings?tab=account","return_to":"http://127.0.0.1:50123/cloud-signin"}""", body.json)
    }

    @Test
    fun aPlainStartSignsInAndLandsHome() {
        val body = CloudWire.AppStart.from("https://gamma.example.org/api/auth/cloud/start", "r", "c")
        assertEquals("/", body.next)
        assertFalse(body.link)
        assertEquals("", body.invite)
    }

    @Test
    fun anInvitationGoesAlong() {
        val body = CloudWire.AppStart.from("https://gamma.example.org/api/auth/cloud/start?next=%2F&invite=Ab_3", "r", "c")
        assertEquals("Ab_3", body.invite)
        assertEquals("""{"challenge":"c","invite":"Ab_3","link":false,"next":"/","return_to":"r"}""", body.json)
    }

    @Test
    fun theLoopbackAddressIsTheOneTheServerTakes() {
        assertEquals("http://127.0.0.1:50123/cloud-signin", CloudWire.returnAddress(50123))
        assertEquals("https://gamma.example.org/api/auth/cloud/app-start", CloudWire.appStartUrl(Origin("https", "gamma.example.org")))
    }

    @Test
    fun appStartsAnswer() {
        assertEquals("https://account.gammapdf.com/authorize?state=s",
                     CloudWire.authorizeUrl(200, """{"url": "https://account.gammapdf.com/authorize?state=s"}"""))
        assertNull("a server older than app-start", CloudWire.authorizeUrl(404, """{"detail": "Not Found"}"""))
        assertNull(CloudWire.authorizeUrl(405, ""))
        val refused = assertThrows(Refusal::class.java) {
            CloudWire.authorizeUrl(400, """{"detail": "The app's challenge is not an S256 code challenge."}""")
        }
        assertEquals("The app's challenge is not an S256 code challenge.", refused.message)
        assertEquals("HTTP 502", assertThrows(Refusal::class.java) { CloudWire.authorizeUrl(502, "Bad gateway") }.message)
        assertEquals("a validation error's detail is no sentence", "HTTP 422",
                     assertThrows(Refusal::class.java) { CloudWire.authorizeUrl(422, """{"detail": [{"loc": ["body"]}]}""") }.message)
        assertEquals("only a web address opens in the browser", "HTTP 200",
                     assertThrows(Refusal::class.java) { CloudWire.authorizeUrl(200, """{"url": "javascript:alert(1)"}""") }.message)
    }

    @Test
    fun theClaimIsTheDesktopsFormPost() {
        val request = CloudWire.claimRequest(Origin("https", "gamma.example.org"), "R-1_x", "V~2")
        assertEquals("https://gamma.example.org/api/auth/cloud/app-claim", request.url)
        assertEquals("POST", request.method)
        assertEquals("claim", request.headers["X-Gamma-Desktop"])
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals("result=R-1_x&verifier=V~2", request.body)
    }

    @Test
    fun aFormBodyEscapesWhatAFormMust() {
        assertEquals("a=x%20y%26z%3D%2B", CloudWire.formBody(listOf("a" to "x y&z=+")))
    }

    @Test
    fun theLoopbackTakesTheResultOrTheError() {
        assertEquals(CloudAnswer.Result("abc_DEF-1"),
                     CloudWire.loopbackAnswer("GET /cloud-signin?result=abc_DEF-1 HTTP/1.1\r\nHost: 127.0.0.1:50123\r\n\r\n"))
        assertEquals("the server's urlencode writes spaces as +", CloudAnswer.Error("Sign-in cancelled."),
                     CloudWire.loopbackAnswer("GET /cloud-signin?error=Sign-in+cancelled. HTTP/1.1\r\n\r\n"))
        assertEquals(CloudAnswer.Error("a+b"), CloudWire.loopbackAnswer("GET /cloud-signin?error=a%2Bb HTTP/1.1\r\n\r\n"))
        assertNull(CloudWire.loopbackAnswer("GET /favicon.ico HTTP/1.1\r\n\r\n"))
        assertNull(CloudWire.loopbackAnswer("POST /cloud-signin?result=abc HTTP/1.1\r\n\r\n"))
        assertNull(CloudWire.loopbackAnswer("GET /cloud-signin HTTP/1.1\r\n\r\n"))
        assertNull(CloudWire.loopbackAnswer("GET /cloud-signin?result= HTTP/1.1\r\n\r\n"))
        assertNull(CloudWire.loopbackAnswer("GET //elsewhere/cloud-signin?result=abc HTTP/1.1\r\n\r\n"))
        assertNull(CloudWire.loopbackAnswer("GET @elsewhere/cloud-signin?result=abc HTTP/1.1\r\n\r\n"))
        assertNull(CloudWire.loopbackAnswer(""))
    }

    @Test
    fun theLoopbackSendsTheBrowserToTheAppsScheme() {
        val found = String(CloudWire.loopbackResponse(CloudAnswer.Result("r")), Charsets.US_ASCII)
        assertTrue(found.startsWith("HTTP/1.1 302 Found\r\n"))
        assertTrue(found.contains("\r\nLocation: gamma-android://cloud-signin\r\n"))
        assertTrue(found.endsWith("\r\n\r\n"))
        assertTrue(String(CloudWire.loopbackResponse(CloudAnswer.Error("e")), Charsets.US_ASCII).startsWith("HTTP/1.1 302"))
        assertTrue(String(CloudWire.loopbackResponse(null), Charsets.US_ASCII).startsWith("HTTP/1.1 404"))
    }
}
