package com.gammapdf.gamma.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import com.gammapdf.gamma.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A local server that does what it is told: its state is the test's. */
class FakeLocal(state: LocalState = LocalState.Stopped) : LocalHost {
    override var state: LocalState = state
        set(value) {
            field = value
            onChange?.invoke()
        }
    override var slowStart = false
    override val url: String? get() = (state as? LocalState.Running)?.url
    override var login: Credentials? = null
    override var onChange: (() -> Unit)? = null
    var log: String? = "Traceback: the port is taken"
    var starts = 0
    var restarts = 0

    override fun start() {
        starts += 1
        if (state == LocalState.Stopped) state = LocalState.Starting
    }

    override fun restart() {
        restarts += 1
        if (state != LocalState.Starting) state = LocalState.Starting
    }

    override fun recheck() {}

    override fun logTail(): String? = log

    override suspend fun dataSize(): Long = 0
}

/**
 * Opening, retrying and the launcher (app/AppModel.kt), ported from
 * ipad/GammaIPadTests/ChromeTests.swift LauncherTests, with the local server
 * and the health probes, which the iPad's tests leave to a device, played
 * by fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModelTest {
    private val a = "https://a.example"
    private val b = "https://b.example"
    private val scope = TestScope(UnconfinedTestDispatcher())
    private var clock = 1_000_000L
    private val asked = mutableListOf<String>()
    private var answer: suspend (String) -> Boolean = { true }

    private fun model(saved: SavedServers, prefs: MemoryPrefs = MemoryPrefs(), local: FakeLocal = FakeLocal()): Pair<AppModel, MemoryPrefs> {
        if (prefs.string(SavedServers.KEY) == null) saved.save(prefs)
        val model = AppModel(prefs, local, { url -> asked += url; answer(url) }, scope, { clock })
        return model to prefs
    }

    @Test
    fun theFirstLaunchShowsTheLauncher() {
        val (first, _) = model(SavedServers(null, emptyList()))
        assertNotNull(first.launcher)
        assertNull(first.launcher?.error)
        assertNull(first.shellUrl)
        val (again, _) = model(SavedServers(Server.Remote(a), listOf(a)))
        assertNull("a relaunch reopens the last server", again.launcher)
        assertEquals(a, again.shellUrl)
    }

    @Test
    fun theLauncherToggles() {
        val (model, _) = model(SavedServers(Server.Remote(a), listOf(a)))
        model.toggleLauncher()
        assertNotNull(model.launcher)
        model.toggleLauncher()
        assertNull(model.launcher)
        model.loadFailed("net::ERR_INTERNET_DISCONNECTED")
        assertNotNull(model.launcher!!.error)
        model.toggleLauncher()
        assertNotNull("one showing an error is not closed but opened afresh", model.launcher)
        assertNull(model.launcher?.error)
        assertNotEquals("each showing is a load of its own", AppModel.Launcher("x"), AppModel.Launcher("x"))
    }

    @Test
    fun openingTheServerShownOnlyClosesTheLauncher() {
        val (model, prefs) = model(SavedServers(Server.Remote(a), listOf(a, b)))
        model.toggleLauncher()
        model.open(Server.Remote(a))
        assertNull(model.launcher)
        assertEquals("no reload", 0, model.connects)
        model.open(Server.Remote(b))
        assertEquals(Server.Remote(b), model.current)
        assertEquals(b, model.shellUrl)
        assertEquals(1, model.connects)
        assertEquals(Server.Remote(b), SavedServers.load(prefs).current)
    }

    @Test
    fun aLoadErrorGoesToTheLauncherAndOpeningRetries() {
        val (model, _) = model(SavedServers(Server.Remote(a), listOf(a)))
        model.loadFailed("net::ERR_INTERNET_DISCONNECTED")
        assertEquals("net::ERR_INTERNET_DISCONNECTED", model.loadError)
        val payload = Json.objectOrNull(model.launcher!!.error)!!
        assertEquals("Gamma cannot reach a.example", payload["summary"])
        assertEquals("https://a.example", payload["server"])
        assertEquals("net::ERR_INTERNET_DISCONNECTED", payload["detail"])
        model.open(Server.Remote(a))
        assertNull(model.launcher)
        assertEquals("the same server, loaded again", 1, model.connects)
        model.loadFailed("Still offline.")
        model.pageLoaded()
        assertNull(model.loadError)
        assertNull("a page arriving closes the launcher showing its error", model.launcher)
        model.toggleLauncher()
        model.pageLoaded()
        assertNotNull("and leaves one opened by hand", model.launcher)
    }

    @Test
    fun aLoadErrorWithNothingShownOnlyNotesIt() {
        val (model, _) = model(SavedServers(null, emptyList()))
        val first = model.launcher
        model.loadFailed("net::ERR_FAILED")
        assertEquals("net::ERR_FAILED", model.loadError)
        assertSame(first, model.launcher)
    }

    @Test
    fun serversAreAddedAtTheEndAndKeepTheirNames() {
        val (model, prefs) = model(SavedServers(Server.Remote(a), listOf(a)))
        model.add(b)
        assertEquals(listOf(a, b), model.remotes)
        model.add(b)
        assertEquals("listed once", listOf(a, b), model.remotes)
        model.rename(Server.Remote(b), "  Lab  ")
        assertEquals(mapOf("https://b.example" to "Lab"), model.names)
        val c = "http://nas.local:9001"
        model.replace(b, c)
        assertEquals(listOf(a, c), model.remotes)
        assertEquals("the name goes with the new address", mapOf("http://nas.local:9001" to "Lab"), model.names)
        model.rename(Server.Local, "Studio")
        assertEquals("Studio", SavedServers.load(prefs).name(Server.Local))
        model.rename(Server.Local, " \n")
        assertEquals("a blank name gives back its own", "This device", model.saved.name(Server.Local))
        model.remove(c)
        assertEquals(listOf(a), model.remotes)
        assertTrue(model.names.isEmpty())
        assertNull(SavedServers.load(prefs).names)
        assertFalse("names" in Json.objectOrNull(prefs.string(SavedServers.KEY))!!)
        model.open(Server.Remote(b))
        assertEquals("opening never lists a server", listOf(a), model.remotes)
    }

    @Test
    fun replacingTheServerShownMovesThePageAndOntoAListedOneMerges() {
        val (model, prefs) = model(SavedServers(Server.Remote(a), listOf(a, b)))
        val c = "https://c.example"
        model.replace(a, c)
        assertEquals(Server.Remote(c), model.current)
        assertEquals("the page loads its new address", c, model.shellUrl)
        assertEquals("not counted as an open", 0, model.connects)
        assertEquals(listOf(c, b), model.remotes)
        model.replace(c, b)
        assertEquals("an address already listed is not listed twice", listOf(b), model.remotes)
        assertEquals(Server.Remote(b), SavedServers.load(prefs).current)
        model.replace("https://unlisted.example", a)
        assertEquals(listOf(b), model.remotes)
    }

    @Test
    fun theHiddenBarIsKeptAndComesBackWhileItIsNeeded() {
        val (model, prefs) = model(SavedServers(Server.Remote(a), listOf(a)))
        assertFalse("up at first", model.barHidden)
        assertTrue(model.barShown)
        assertFalse("so its hide button shows", model.barNeeded)
        model.setBarHidden(true)
        assertFalse(model.barShown)
        assertTrue("kept for the next launch", model(SavedServers(null, emptyList()), prefs).first.barHidden)
        model.toggleLauncher()
        assertTrue(model.barNeeded)
        assertTrue("up with the launcher, whose mark closes it", model.barShown)
        model.toggleLauncher()
        assertFalse("and away again once it closes", model.barShown)
        model.loadFailed("net::ERR_INTERNET_DISCONNECTED")
        assertTrue("a failure in the launcher too", model.barShown)
        model.setBarHidden(false)
        assertFalse(model(SavedServers(null, emptyList()), prefs).first.barHidden)
    }

    @Test
    fun removingTheCurrentServerUnchoosesIt() {
        val (model, prefs) = model(SavedServers(Server.Remote(a), listOf(a, b)))
        model.remove(b)
        assertEquals(Server.Remote(a), model.current)
        model.remove(a)
        assertNull(model.current)
        assertEquals("its page stays under the launcher", a, model.shellUrl)
        assertNull("the next launch shows the launcher", SavedServers.load(prefs).current)
        assertFalse("current" in Json.objectOrNull(prefs.string(SavedServers.KEY))!!)
    }

    @Test
    fun thisDeviceStartsWhenChosenAndIsShownOnceItRuns() {
        val local = FakeLocal()
        val (model, _) = model(SavedServers(Server.Local, emptyList()), local = local)
        assertEquals("started at launch", 1, local.starts)
        assertEquals(LocalState.Starting, local.state)
        assertNull("nothing to show yet", model.shellUrl)
        assertNull("nothing covers the empty page", model.launcher)
        assertTrue("the bar says it starts", model.barNeeded)
        var changes = 0
        model.onChange = { changes += 1 }
        local.state = LocalState.Running("http://127.0.0.1:50123")
        assertEquals("http://127.0.0.1:50123", model.shellUrl)
        assertEquals("the shell follows", 1, changes)
        assertFalse(model.barNeeded)
        local.login = Credentials(Credentials.ACCOUNT, "abcdefghijkl")
        assertEquals(local.login, model.login("http://127.0.0.1:50123"))
        assertNull("the account signs in nowhere else", model.login("https://a.example"))
        assertNull(model.login(null))
        local.slowStart = true
        local.onChange?.invoke()
        assertEquals("a change of the server that is not its state is followed too", 2, changes)
        assertEquals("http://127.0.0.1:50123", model.shellUrl)
    }

    @Test
    fun aFailedStartGoesToTheLauncherWithTheLogAndOpeningRestarts() {
        val local = FakeLocal()
        val (model, _) = model(SavedServers(Server.Local, emptyList()), local = local)
        local.state = LocalState.Failed("The server stopped before it answered.")
        val payload = Json.objectOrNull(model.launcher!!.error)!!
        assertEquals("Gamma could not start on this device", payload["summary"])
        assertEquals("The server stopped before it answered.", payload["detail"])
        assertEquals("Traceback: the port is taken", payload["log"])
        assertEquals("local", payload["server"])
        val shown = model.launcher
        local.onChange?.invoke()
        assertSame("the same failure is not shown again", shown, model.launcher)
        model.open(Server.Local)
        assertEquals("Try again starts it again", 1, local.restarts)
        assertNotNull("the launcher stays up until it runs", model.launcher)
        local.state = LocalState.Running("http://127.0.0.1:50123")
        assertNull(model.launcher)
        assertEquals("http://127.0.0.1:50123", model.shellUrl)
        model.open(Server.Local)
        assertEquals("running and shown: nothing to do", 1, local.restarts)
    }

    @Test
    fun aFailedStartWithoutALogOffersNone() {
        val local = FakeLocal().apply { log = null }
        val (model, _) = model(SavedServers(Server.Local, emptyList()), local = local)
        local.state = LocalState.Failed("Gamma found no free port on this device.")
        assertFalse("log" in Json.objectOrNull(model.launcher!!.error)!!)
    }

    @Test
    fun thisDeviceIsLeftAloneWhileAnotherServerIsChosen() {
        val local = FakeLocal()
        val (model, _) = model(SavedServers(Server.Remote(a), listOf(a)), local = local)
        assertEquals("not started for a remote", 0, local.starts)
        local.state = LocalState.Failed("whatever")
        assertNull(model.launcher)
        local.state = LocalState.Running("http://127.0.0.1:50123")
        assertEquals(a, model.shellUrl)
        model.open(Server.Local)
        assertEquals("a running server is shown at once", "http://127.0.0.1:50123", model.shellUrl)
        assertEquals(1, model.connects)
        assertEquals(0, local.restarts)
    }

    @Test
    fun aServerIsAskedForItsHealthAtMostEveryThirtySeconds() {
        val (model, _) = model(SavedServers(Server.Remote(a), listOf(a, b)))
        answer = { it == a }
        model.probe(model.remotes)
        assertEquals(listOf(a, b), asked)
        assertEquals(mapOf(a to true, b to false), model.reachable)
        clock += 29_999
        model.probe(model.remotes)
        assertEquals("answered just now", 2, asked.size)
        model.probe(listOf(a), force = true)
        assertEquals(3, asked.size)
        clock += 1
        model.probe(model.remotes)
        assertEquals("b's answer is old", listOf(a, b, a, b), asked)
        model.add("https://c.example")
        assertEquals("a server added is asked at once", "https://c.example", asked.last())
        model.remove(b)
        assertFalse(b in model.reachable)
    }

    @Test
    fun onlyTheLatestAnswerCounts() {
        val (model, _) = model(SavedServers(Server.Remote(a), listOf(a, b)))
        val slow = CompletableDeferred<Boolean>()
        answer = { slow.await() }
        model.probe(listOf(a))
        answer = { true }
        model.probe(listOf(a), force = true)
        assertEquals(true, model.reachable[a])
        slow.complete(false)
        assertEquals("the earlier ask's answer came late", true, model.reachable[a])

        val pending = CompletableDeferred<Boolean>()
        answer = { pending.await() }
        model.probe(listOf(b))
        model.remove(b)
        pending.complete(true)
        assertFalse("none for a server taken off the list", b in model.reachable)

        answer = { throw IllegalStateException("no network") }
        model.probe(listOf(a), force = true)
        assertEquals("a probe that fails is a server that is down", false, model.reachable[a])
    }

    @Test
    fun everyChangeIsTheShellsToFollow() {
        val (model, _) = model(SavedServers(Server.Remote(a), listOf(a)))
        var changes = 0
        model.onChange = { changes += 1 }
        model.toggleLauncher()
        model.setBarHidden(true)
        model.setBarHidden(true)
        model.add(b)
        assertEquals("the toggle, the bar, the add and its probe's answer; not a choice already made", 4, changes)
    }

    @Test
    fun whatWasKeptIsReadBackAndWhatCannotBeReadIsNothing() {
        val prefs = MemoryPrefs()
        SavedServers(Server.Remote(b), listOf(a, b), mapOf("local" to "Studio", b to "Lab")).save(prefs)
        assertEquals(SavedServers(Server.Remote(b), listOf(a, b), mapOf("local" to "Studio", b to "Lab")), SavedServers.load(prefs))
        SavedServers(Server.Local, emptyList()).save(prefs)
        assertEquals(SavedServers(Server.Local, emptyList()), SavedServers.load(prefs))
        prefs.putString(SavedServers.KEY, "{not json")
        assertEquals(SavedServers(null, emptyList()), SavedServers.load(prefs))
        prefs.putString(SavedServers.KEY, """{"current":"","remotes":[7,"https://a.example"],"names":{"local":3}}""")
        assertEquals(SavedServers(null, listOf(a)), SavedServers.load(prefs))
    }
}
