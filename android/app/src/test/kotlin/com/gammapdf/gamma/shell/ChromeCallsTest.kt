package com.gammapdf.gamma.shell

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import com.gammapdf.gamma.app.AppModel
import com.gammapdf.gamma.app.FakeLocal
import com.gammapdf.gamma.app.LocalState
import com.gammapdf.gamma.app.MemoryPrefs
import com.gammapdf.gamma.app.SavedServers
import com.gammapdf.gamma.app.Server
import com.gammapdf.gamma.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What the device does for a call, as the calls asked it. */
private class RecordingHost : ChromeHost {
    override val version = "1.2.3"
    override var themeName = "gamma-dark"
    override var session: Map<String, Any?>? = null
    override var address: String? = null
    val did = mutableListOf<String>()

    override fun readSession() { did += "readSession" }
    override fun openWorkspace(id: String) { did += "openWorkspace $id" }
    override fun reload() { did += "reload" }
    override fun expandBar(on: Boolean) { did += "expandBar $on" }
    override fun closeBarMenu() { did += "closeBarMenu" }
    override fun revealData() { did += "revealData" }
    override fun revealLog() { did += "revealLog" }
    override fun copy(text: String) { did += "copy $text" }
}

/**
 * The chrome pages' `gammaShell` calls (shell/ChromeCalls.kt), as
 * ShellController.call answers them on the iPad (ipad/GammaIPad/Shell/
 * ShellView.swift), with the Android sentences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChromeCallsTest {
    private val a = "https://a.example"
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val local = FakeLocal()
    private val host = RecordingHost()

    private fun calls(saved: SavedServers = SavedServers(Server.Remote(a), listOf(a))): Pair<ChromeCalls, AppModel> {
        val prefs = MemoryPrefs()
        saved.save(prefs)
        val model = AppModel(prefs, local, { true }, scope, { 1_000_000L })
        return ChromeCalls(model, host) to model
    }

    private suspend fun refused(calls: ChromeCalls, method: String, vararg args: Any?): String {
        try {
            calls.call(method, args.toList())
        } catch (e: Refusal) {
            return e.message.orEmpty()
        }
        fail("$method was not refused")
        error("unreachable")
    }

    @Test
    fun anUnknownMethodOrArgumentIsRefused() = runTest {
        val (calls, _) = calls()
        assertEquals("Gamma for Android has no openPath.", refused(calls, "openPath", "/tmp"))
        assertEquals("Gamma for Android has no keepOffline.", refused(calls, "keepOffline"))
        assertEquals("Gamma for Android cannot read this call to open.", refused(calls, "open"))
        assertEquals("Gamma for Android cannot read this call to open.", refused(calls, "open", 7))
        assertEquals("Gamma for Android cannot read this call to barExpand.", refused(calls, "barExpand", "yes"))
        assertEquals("Gamma for Android cannot read this call to rename.", refused(calls, "rename", "local"))
        assertEquals("Gamma for Android cannot read this call to openWorkspace.", refused(calls, "openWorkspace"))
        assertTrue("nothing was done for a refused call", host.did.isEmpty())
    }

    @Test
    fun onlyListedServersOpen() = runTest {
        val (calls, model) = calls()
        assertEquals("https://b.example is not a listed server.", refused(calls, "open", "https://b.example"))
        assertEquals(" is not a listed server.", refused(calls, "open", ""))
        assertNull(calls.call("open", listOf("local")))
        assertEquals(Server.Local, model.current)
        assertEquals("this device starts", LocalState.Starting, local.state)
        calls.call("open", listOf(a))
        assertEquals(Server.Remote(a), model.current)
    }

    @Test
    fun addRemoteListsAndNamesAServer() = runTest {
        val (calls, model) = calls()
        assertEquals("That is not a web address.", refused(calls, "addRemote", "NAS", "ftp://nas.local"))
        assertEquals("https://a.example is already listed", refused(calls, "addRemote", "Again", "a.example/some/page"))
        assertNull(calls.call("addRemote", listOf("NAS", "http://NAS.local:9001/p?x=1")))
        assertEquals(listOf(a, "http://nas.local:9001"), model.remotes)
        assertEquals("NAS", model.names["http://nas.local:9001"])
        assertEquals("added, not opened", Server.Remote(a), model.current)
    }

    @Test
    fun thisDeviceKeepsItsAddressAndItsPlace() = runTest {
        val (calls, model) = calls()
        assertEquals("This device's address is its own.", refused(calls, "setUrl", "local", "https://c.example"))
        assertEquals("This device cannot be removed.", refused(calls, "remove", "local", emptyMap<String, Any?>()))
        assertEquals("That is not a web address.", refused(calls, "setUrl", a, "https://"))
        calls.call("rename", listOf("local", "  Studio "))
        assertEquals("Studio", model.names["local"])
        calls.call("setUrl", listOf(a, "c.example"))
        assertEquals(listOf("https://c.example"), model.remotes)
        assertEquals(Server.Remote("https://c.example"), model.current)
        calls.call("remove", listOf("https://c.example", emptyMap<String, Any?>()))
        assertTrue(model.remotes.isEmpty())
        assertNull(model.current)
    }

    @Test
    fun copyAddressCopiesOnlyARunningServersAddress() = runTest {
        val (calls, _) = calls()
        assertNull(calls.call("copyAddress", listOf("local")))
        assertTrue(host.did.isEmpty())
        local.state = LocalState.Running("http://127.0.0.1:9123")
        assertEquals("http://127.0.0.1:9123", calls.call("copyAddress", listOf("local")))
        assertEquals(listOf("copy http://127.0.0.1:9123"), host.did)
    }

    @Test
    fun stateReadsTheSessionAndListCountsTheData() = runTest {
        val (calls, _) = calls()
        host.session = mapOf("user" to mapOf("username" to "tim"), "default_workspace" to "w1", "workspaces" to listOf("w1", "w2"))
        host.address = "$a/?ws=w2"
        @Suppress("UNCHECKED_CAST")
        val state = calls.call("state", emptyList()) as Map<String, Any?>
        assertEquals(listOf("readSession"), host.did)
        assertEquals("1.2.3", state["version"])
        assertEquals("gamma-dark", state["theme"])
        assertEquals(true, state["barHideable"])
        @Suppress("UNCHECKED_CAST")
        val gamma = state["gamma"] as Map<String, Any?>
        assertEquals("w2", gamma["current"])
        @Suppress("UNCHECKED_CAST")
        val device = (state["servers"] as List<Map<String, Any?>>).first()
        assertFalse("state leaves the data size out", "sizeBytes" in device)

        host.did.clear()
        @Suppress("UNCHECKED_CAST")
        val listed = calls.call("list", emptyList()) as Map<String, Any?>
        assertTrue("a list reads no session: a read pushes, and the launcher lists on every push", host.did.isEmpty())
        @Suppress("UNCHECKED_CAST")
        val counted = (listed["servers"] as List<Map<String, Any?>>).first()
        assertEquals(0L, counted["sizeBytes"])
        assertNotNull("the state is JSON", Json.parseOrNull(Json.write(listed)))
    }

    @Test
    fun theBarsCallsReachTheDevice() = runTest {
        val (calls, model) = calls()
        calls.call("barExpand", listOf(true))
        calls.call("openWorkspace", listOf("w 2"))
        calls.call("reload", emptyList())
        calls.call("revealData", listOf("local"))
        calls.call("revealLog", listOf("local"))
        assertEquals(listOf("expandBar true", "expandBar false", "openWorkspace w 2", "reload", "revealData", "revealLog"), host.did)
        calls.call("hideBar", emptyList())
        assertTrue(model.barHidden)
    }

    @Test
    fun theLauncherClosesTheBarsMenuFirst() = runTest {
        val (calls, model) = calls()
        assertNull(model.launcher)
        calls.call("launcher", emptyList())
        assertNotNull(model.launcher)
        assertEquals(listOf("closeBarMenu"), host.did)
        calls.toggleLauncher()
        assertNull(model.launcher)
        assertEquals(listOf("closeBarMenu", "closeBarMenu"), host.did)
    }

    @Test
    fun theBarIsHideableOnlyWhenNotNeeded() {
        val (calls, _) = calls(SavedServers(null, emptyList()))
        assertEquals("the first launch's launcher needs the bar", false, calls.state()["barHideable"])
        assertNull(calls.state()["current"])
        assertFalse("lastOpened" in calls.state())
    }
}
