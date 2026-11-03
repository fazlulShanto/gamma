package com.gammapdf.gamma.shell

import com.gammapdf.gamma.app.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The session read and the silent sign-in's server side (shell/SessionWire.kt),
 * read as the iPad's WebShell scripts read it (ipad/GammaIPad/Shell/WebShell.swift).
 */
class SessionWireTest {
    @Test
    fun aSignedInSessionGivesTheSwitchersWorkspaces() {
        val session = SessionWire.session(200, """{"user":{"username":"tim"},"default_workspace":"w1","workspaces":[{"id":"w1"},{"id":"w2"}],"csrf":"x"}""")
        assertEquals(listOf("user", "default_workspace", "workspaces"), session?.keys?.toList())
        assertEquals(mapOf("username" to "tim"), session?.get("user"))
        assertEquals("w1", session?.get("default_workspace"))
        assertEquals(2, (session?.get("workspaces") as List<*>).size)
    }

    @Test
    fun missingPartsReadAsTheScriptReadsThem() {
        val bare = SessionWire.session(200, """{"user":"tim"}""")
        assertEquals("tim", bare?.get("user"))
        assertNull("no default workspace is null", bare?.get("default_workspace"))
        assertEquals("no workspaces are none", emptyList<Any?>(), bare?.get("workspaces"))
        assertEquals(emptyList<Any?>(), SessionWire.session(200, """{"user":"tim","workspaces":null}""")?.get("workspaces"))
    }

    @Test
    fun noOneSignedInIsNoSession() {
        for (body in listOf("""{"user":null}""", """{"user":""}""", """{"user":false}""", """{}""", "[]", "not json", "")) {
            assertNull(body, SessionWire.session(200, body))
        }
        assertNull(SessionWire.session(200, null))
        assertNull("an error answer is no session", SessionWire.session(401, """{"user":"tim"}"""))
        assertNull(SessionWire.session(500, """{"user":"tim"}"""))
    }

    @Test
    fun theLoginBodyIsTheAccount() {
        assertEquals("""{"username":"admin","password":"p\"w\\d"}""", SessionWire.loginBody(Credentials("admin", "p\"w\\d")))
    }

    @Test
    fun setCookieHeadersAreFoundWhateverTheirCase() {
        val headers = mapOf<String?, List<String>?>(
            null to listOf("HTTP/1.1 200 OK"),
            "Content-Type" to listOf("application/json"),
            "set-cookie" to listOf("gamma_session=abc; Path=/; HttpOnly"),
            "Set-Cookie" to listOf("other=1; Path=/"),
            "X-Empty" to null,
        )
        assertEquals(listOf("gamma_session=abc; Path=/; HttpOnly", "other=1; Path=/"), SessionWire.setCookies(headers))
        assertEquals(emptyList<String>(), SessionWire.setCookies(mapOf(null to listOf("HTTP/1.1 200 OK"))))
    }
}
