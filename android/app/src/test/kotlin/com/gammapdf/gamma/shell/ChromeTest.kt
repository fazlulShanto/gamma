package com.gammapdf.gamma.shell

import java.net.URLDecoder
import com.gammapdf.gamma.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop's chrome in the app without a WebView: who may call it, and
 * the launcher's failure (ChromePages, ShellFailure), ported from
 * ipad/GammaIPadTests/ChromeTests.swift.
 */
class ChromeTest {
    @Test
    fun onlyTheBundledPagesCall() {
        assertTrue(ChromePages.accepts(ChromePages.BAR, mainFrame = true))
        assertTrue(ChromePages.accepts(ChromePages.launcher("{}"), mainFrame = true))
        assertFalse("a frame inside the page", ChromePages.accepts(ChromePages.BAR, mainFrame = false))
        assertFalse("a server's page", ChromePages.accepts("https://gamma.example.org/", mainFrame = true))
        assertFalse("another of the loader's paths", ChromePages.accepts("https://appassets.androidplatform.net/assets/shell/host.js", true))
        assertFalse("plain http is another origin", ChromePages.accepts("http://appassets.androidplatform.net/assets/chrome/bar.html", true))
        assertFalse(ChromePages.accepts(null, mainFrame = true))
    }

    @Test
    fun theLauncherCarriesTheFailureAsTheDesktopSendsIt() {
        assertEquals("https://appassets.androidplatform.net/assets/chrome/launcher.html", ChromePages.launcher(null))
        val log = "C++ & friends = \"trouble\"\nline 2 · ü"
        val json = ShellFailure.localFailed("port 50123 is in use", log).json
        val url = ChromePages.launcher(json)
        assertTrue(url.startsWith(ChromePages.LAUNCHER + "?error="))
        val query = url.substringAfter('?')
        assertFalse("a + would read as a space", '+' in query)
        // as the page's URLSearchParams reads it, a + as a space
        val error = URLDecoder.decode(query.removePrefix("error="), "UTF-8")
        assertEquals(json, error)
        assertEquals(json, UrlParts.parse(url)?.queryValue("error"))
        val payload = Json.objectOrNull(error)!!
        assertEquals("Gamma could not start on this device", payload["summary"])
        assertEquals("port 50123 is in use", payload["detail"])
        assertEquals(log, payload["log"])
        assertEquals("retry", payload["action"])
        assertEquals("local", payload["server"])
        val order = listOf("summary", "detail", "log", "action", "server").map { json.indexOf("\"$it\":") }
        assertEquals("the keys in the iPad's order", order.sorted(), order)

        val unreachable = Json.objectOrNull(ShellFailure.unreachable("http://nas.local:9001", "http://nas.local:9001", "offline").json)!!
        assertEquals("Gamma cannot reach nas.local", unreachable["summary"])
        assertEquals("http://nas.local:9001", unreachable["server"])
        assertEquals("offline", unreachable["detail"])
        assertFalse("no log for a server elsewhere", "log" in unreachable)
    }

    @Test
    fun aStartWithoutALogOffersNone() {
        assertFalse("log" in Json.objectOrNull(ShellFailure.localFailed("no free port", "").json)!!)
    }

    @Test
    fun percentEncodingLeavesOnlyTheUnreserved() {
        assertEquals("A-z_0.9~", percentEncode("A-z_0.9~"))
        assertEquals("%20%2B%26%3D%3F%2F%25", percentEncode(" +&=?/%"))
        assertEquals("%C3%BC%F0%9F%91%BB", percentEncode("ü👻"))
    }
}
