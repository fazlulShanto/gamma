package com.gammapdf.gamma.app

import com.gammapdf.gamma.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state the chrome pages read (app/ShellState.kt) and the servers' names
 * (SavedServers), ported from ipad/GammaIPadTests/ChromeTests.swift ShellStateTests.
 */
class ShellStateTest {
    private val a = "https://a.example"
    private val nas = "http://nas.local:9001"
    private val address = "http://127.0.0.1:50123"
    private val session: Map<String, Any?> = mapOf(
        "user" to mapOf("username" to "admin"),
        "default_workspace" to "w1",
        "workspaces" to listOf(mapOf("id" to "w1", "name" to "Personal"), mapOf("id" to "w2", "name" to "Lab")),
    )

    private fun state(
        current: Server? = Server.Local,
        remotes: List<String> = emptyList(),
        names: Map<String, String>? = null,
        reachable: Map<String, Boolean> = emptyMap(),
        local: LocalState = LocalState.Stopped,
        slowStart: Boolean = false,
        login: Credentials? = null,
        session: Map<String, Any?>? = null,
        page: String? = null,
        barHideable: Boolean = true,
        sizeBytes: Long? = null,
    ): Map<String, Any?> = ShellState.make("0.2.0", "gamma-dark", SavedServers(current, remotes, names), reachable, local,
                                           slowStart, login, session, page, barHideable, sizeBytes)

    @Suppress("UNCHECKED_CAST")
    private fun servers(state: Map<String, Any?>) = state["servers"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun gamma(state: Map<String, Any?>) = state["gamma"] as Map<String, Any?>?

    @Test
    fun thisDeviceComesFirstThenTheRemotesInTheirOrder() {
        val built = state(current = Server.Remote(nas), remotes = listOf(a, nas), names = mapOf(nas to "NAS"), reachable = mapOf(a to false))
        val list = servers(built)
        assertEquals(listOf("local", "https://a.example", "http://nas.local:9001"), list.map { it["id"] })
        assertEquals(listOf("This device", "a.example", "NAS"), list.map { it["name"] })
        assertEquals(listOf("local", "remote", "remote"), list.map { it["type"] })
        assertEquals("https://a.example", list[1]["url"])
        assertEquals(false, list[1]["reachable"])
        assertFalse("not asked yet", "reachable" in list[2])
        assertEquals(mapOf("id" to "http://nas.local:9001", "name" to "NAS", "type" to "remote"), built["current"])
        assertEquals("http://nas.local:9001", built["lastOpened"])
        assertEquals("0.2.0", built["version"])
        assertEquals("gamma-dark", built["theme"])
    }

    @Test
    fun aServerWithoutANameIsNamedByWhatItIs() {
        val saved = SavedServers(null, listOf(a, nas), mapOf("local" to "Studio"))
        assertEquals("Studio", saved.name(Server.Local))
        assertEquals("a.example", saved.name(Server.Remote(a)))
        assertEquals("with its port", "nas.local:9001", saved.name(Server.Remote(nas)))
        assertEquals("[::1]:9001", saved.name(Server.Remote("http://[::1]:9001")))
        assertEquals("This device", SavedServers(null, emptyList()).name(Server.Local))
        val none = state(current = null)
        assertTrue("nothing chosen yet", "current" in none)
        assertNull(none["current"])
        assertFalse("lastOpened" in none)
    }

    @Test
    fun thisDevicesCardHasItsAccountAndFolder() {
        val stopped = servers(state())[0]
        assertEquals(false, stopped["running"])
        assertFalse("no address until it runs", "url" in stopped)
        assertFalse("only the launcher asks for the size", "sizeBytes" in stopped)
        assertEquals("App storage › Gamma › local", stopped["dataDir"])
        assertEquals("admin", stopped["adminUser"])
        assertEquals("no password kept for it", ShellState.NO_PASSWORD, stopped["adminPassword"])
        assertEquals("This device keeps no password for this account. A password the server made itself is in its log.", ShellState.NO_PASSWORD)

        val running = servers(state(local = LocalState.Running(address), login = Credentials(Credentials.ACCOUNT, "abcdefghijkl"),
                                    sizeBytes = 123_456))[0]
        assertEquals(true, running["running"])
        assertEquals("http://127.0.0.1:50123", running["url"])
        assertEquals(123_456L, running["sizeBytes"])
        assertEquals("abcdefghijkl", running["adminPassword"])
    }

    @Test
    fun theBarSaysThisDeviceIsStarting() {
        assertEquals("Starting This device…", state(local = LocalState.Starting)["busy"])
        assertEquals("Still starting This device… the first start after an install or an update can take a few minutes",
                     state(local = LocalState.Starting, slowStart = true)["busy"])
        assertEquals("Starting Studio…", state(names = mapOf("local" to "Studio"), local = LocalState.Starting)["busy"])
        val running = state(local = LocalState.Running(address))
        assertTrue("busy" in running)
        assertNull(running["busy"])
        assertNull("this device starting while another server is chosen",
                   state(current = Server.Remote(a), remotes = listOf(a), local = LocalState.Starting)["busy"])
        assertNull(state(local = LocalState.Failed("no port"))["busy"])
    }

    @Test
    fun theWorkspacesComeFromTheSessionOfThePagesServer() {
        val page = "$address/p/abc"
        val gamma = gamma(state(local = LocalState.Running(address), session = session, page = page))!!
        assertEquals("the account's default", "w1", gamma["current"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("w1", "w2"), (gamma["list"] as List<Map<String, Any?>>).map { it["id"] })
        assertEquals(mapOf("username" to "admin"), gamma["user"])

        val shown = gamma(state(local = LocalState.Running(address), session = session, page = "http://127.0.0.1:50123/?ws=w2"))!!
        assertEquals("the page's ?ws=", "w2", shown["current"])

        assertNull("no one signed in", gamma(state(local = LocalState.Running(address), page = page)))
        assertNull("no user in the session", gamma(state(local = LocalState.Running(address), session = mapOf("user" to null), page = page)))
        assertNull("the page is not on the chosen server", gamma(state(local = LocalState.Starting, session = session, page = page)))
        assertNull("nor on another server's",
                   gamma(state(current = Server.Remote(a), remotes = listOf(a), local = LocalState.Running(address), session = session, page = page)))
        val remote = gamma(state(current = Server.Remote(a), remotes = listOf(a), session = mapOf("user" to "u"), page = "https://a.example/"))!!
        assertEquals("a session without workspaces lists none", emptyList<Any?>(), remote["list"])
    }

    @Test
    fun onlyWhatTheAppHasAndAllOfItJson() {
        assertEquals("no keeping, update or settings: the pages hide what reads them",
                     setOf("version", "theme", "current", "busy", "gamma", "servers", "lastOpened", "barHideable"), state().keys)
        assertEquals("the bar shows its hide button", true, state()["barHideable"])
        assertEquals("not while the bar is needed", false, state(barHideable = false)["barHideable"])
        val full = state(current = Server.Local, remotes = listOf(a, nas), names = mapOf("local" to "Studio"), reachable = mapOf(a to true),
                         local = LocalState.Running(address), login = Credentials(Credentials.ACCOUNT, "abcdefghijkl"),
                         session = session, page = address, sizeBytes = 9_876_543_210)
        val json = Json.write(full)
        assertEquals("what the page reads is what was built", Json.parse(Json.write(Json.parse(json))), Json.parse(json))
        @Suppress("UNCHECKED_CAST")
        val local = (Json.parse(json) as Map<String, Any?>)["servers"].let { (it as List<Map<String, Any?>>)[0] }
        assertEquals(9_876_543_210L, (local["sizeBytes"] as Number).toLong())
    }
}
