package com.gammapdf.gamma.server

import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.gammapdf.gamma.app.Credentials
import com.gammapdf.gamma.app.LocalState
import com.gammapdf.gamma.app.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * LocalServer's start, wait, restart and recheck (the rules of
 * ipad/GammaIPad/Server/LocalServer.swift) over a stand-in interpreter, a
 * stand-in health probe and virtual time; the real interpreter is the device
 * test's (LocalServerStartTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalServerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class MapPrefs : Prefs {
        val values = mutableMapOf<String, Any?>()
        override fun string(key: String) = values[key] as? String
        override fun putString(key: String, value: String?) = put(key, value)
        override fun bool(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBool(key: String, value: Boolean) = put(key, value)
        override fun int(key: String) = values[key] as? Int
        override fun putInt(key: String, value: Int?) = put(key, value)
        private fun put(key: String, value: Any?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class MemoryVault(var password: String? = null, val refuses: Boolean = false) : PasswordVault {
        override fun read() = password
        override fun write(password: String): Boolean {
            if (refuses) return false
            this.password = password
            return true
        }
    }

    /** The interpreter as LocalServer sees it: what it was started with, and the supervisor's answers. */
    private class FakeRuntime : LocalServer.Runtime {
        override var started = false
        var startable = true
        var restartable = true
        var restarts = 0
        var onRestart: () -> Unit = {}
        val environments = mutableListOf<Map<String, String>>()
        var exit: ((String) -> Unit)? = null

        override fun start(environment: Map<String, String>, log: File, onExit: (String) -> Unit): Boolean {
            if (!startable || started) return false
            started = true
            environments += environment
            exit = onExit
            return true
        }

        override fun restart(): Boolean {
            restarts++
            if (restartable) onRestart()
            return restartable
        }
    }

    private val data by lazy { File(folder.root, "files/Gamma/local") }
    private val temp by lazy { File(folder.root, "cache/gamma-tmp") }
    private val assets by lazy { folder.newFolder("assets") }
    private val prefs = MapPrefs()
    private val runtime = FakeRuntime()
    private var vault = MemoryVault()
    private var up = false
    private var probes = 0
    private var port: Int? = 50123
    private val asked = mutableListOf<Int>()
    private var changes = 0

    private fun webApp(): WebAssets {
        File(assets, "frontend_dist").mkdirs()
        File(assets, "frontend_dist/index.html").writeText("<!doctype html>")
        File(assets, "frontend_dist.files").writeText("index.html\n")
        File(assets, "frontend_dist.stamp").writeText("aaa\n")
        return WebAssets(
            { name -> File(assets, name).also { if (!it.isFile) throw FileNotFoundException(name) }.inputStream() },
            File(folder.root, "files/frontend_dist"),
        )
    }

    /**
     * A server on the test's clock, in its background scope: the wait for an
     * answer never ends by itself, and the background scope is cancelled
     * before the test drains what is left.
     */
    private fun TestScope.server(seed: Credentials? = null, webApp: WebAssets = webApp()): LocalServer = LocalServer(
        dataDirectory = data,
        webApp = webApp,
        tempDirectory = temp,
        version = "0.4.0",
        prefs = prefs,
        vault = vault,
        runtime = runtime,
        scope = backgroundScope,
        seed = seed,
        io = StandardTestDispatcher(testScheduler),
        probe = { _, _ ->
            probes++
            up
        },
        pickPort = { remembered ->
            asked += remembered
            port
        },
        clock = { testScheduler.currentTime },
    ).also { it.onChange = { changes++ } }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun write(state: String, detail: String) =
        File(data, "logs/server.state").writeText("""{"state": "$state", "detail": "$detail", "at": "2026-11-02T10:00:00+00:00"}""")

    @Test
    fun theFirstStartSeedsTheAccountAndRunsOnceTheServerAnswers() = runTest {
        val server = server()
        server.start()
        assertEquals(LocalState.Starting, server.state)
        runCurrent()

        val env = runtime.environments.single()
        val password = vault.password!!
        assertEquals(Credentials("admin", password), server.login)
        assertEquals(data.path, env["GAMMA_DATA_DIR"])
        assertEquals(File(folder.root, "files/frontend_dist").path, env["GAMMA_STATIC_DIR"])
        assertTrue("the web app is unpacked before the interpreter", File(folder.root, "files/frontend_dist/index.html").isFile)
        assertEquals(temp.path, env["TMPDIR"])
        assertTrue(temp.isDirectory)
        assertEquals("50123", env["GAMMA_PORT"])
        assertEquals("0.4.0", env["GAMMA_VERSION"])
        assertEquals("admin", env["GAMMA_ADMIN_USER"])
        assertEquals(password, env["GAMMA_ADMIN_PASSWORD"])
        assertEquals(50123, prefs.int(LocalServer.PORT_KEY))
        assertTrue(File(data, "logs").isDirectory)
        assertEquals("http://127.0.0.1:50123", server.url)
        assertEquals("not running until it answers", LocalState.Starting, server.state)

        up = true
        advance(250)
        assertEquals(LocalState.Running("http://127.0.0.1:50123"), server.state)
        assertFalse(server.slowStart)
        assertTrue(changes >= 3)
    }

    @Test
    fun theRememberedPortIsAskedForFirst() = runTest {
        prefs.putInt(LocalServer.PORT_KEY, 50999)
        server().start()
        runCurrent()
        assertEquals(listOf(50999), asked)
    }

    @Test
    fun aUsedDataDirectoryKeepsItsAccounts() = runTest {
        File(data, "users.db").apply { parentFile!!.mkdirs() }.writeText("")
        vault = MemoryVault("kept-pw")
        val server = server(seed = Credentials("admin", "seed-pw"))
        server.start()
        runCurrent()
        assertEquals(Credentials("admin", "kept-pw"), server.login)
        assertNull("the server ignores a seed once it has accounts", runtime.environments.single()["GAMMA_ADMIN_PASSWORD"])
    }

    @Test
    fun aUsedDataDirectoryWithoutItsPasswordHasNoLogin() = runTest {
        File(data, "users.db").apply { parentFile!!.mkdirs() }.writeText("")
        val server = server(seed = Credentials("admin", "seed-pw"))
        server.start()
        runCurrent()
        assertNull(server.login)
        assertNull("no password is made that the server would not know", vault.password)
        assertNull(runtime.environments.single()["GAMMA_ADMIN_USER"])
    }

    @Test
    fun aVaultThatRefusesLeavesTheSeed() = runTest {
        vault = MemoryVault(refuses = true)
        val server = server(seed = Credentials("admin", "start-test-pw"))
        server.start()
        runCurrent()
        assertEquals(Credentials("admin", "start-test-pw"), server.login)
        assertEquals("start-test-pw", runtime.environments.single()["GAMMA_ADMIN_PASSWORD"])
    }

    @Test
    fun aStopTheSupervisorWroteFailsTheStart() = runTest {
        val server = server()
        server.start()
        runCurrent()
        write("stopped", "the server could not listen on its port; the log says why")
        advance(250)
        assertEquals(LocalState.Failed("the server could not listen on its port; the log says why"), server.state)
        val probesThen = probes
        advance(5000)
        assertEquals("no more polling", probesThen, probes)
    }

    @Test
    fun aStopWithoutADetailSaysItStoppedEarly() = runTest {
        val server = server()
        server.start()
        runCurrent()
        write("stopped", "")
        advance(250)
        assertEquals(LocalState.Failed(LocalServer.STOPPED_EARLY), server.state)
    }

    @Test
    fun theLastLaunchsStateFileIsNotRead() = runTest {
        File(data, "logs").mkdirs()
        write("stopped", "the server stopped")
        val server = server()
        server.start()
        advance(1000)
        assertEquals(LocalState.Starting, server.state)
    }

    @Test
    fun aLongStartIsMarkedSlowAndPolledEverySecond() = runTest {
        val server = server()
        server.start()
        runCurrent()
        advance(59_000)
        assertFalse(server.slowStart)
        advance(1_500)
        assertTrue(server.slowStart)
        val probesThen = probes
        advance(5_000)
        assertEquals(5, probes - probesThen)
        up = true
        advance(1_000)
        assertEquals(LocalState.Running("http://127.0.0.1:50123"), server.state)
        assertFalse(server.slowStart)
    }

    @Test
    fun noFreePortFailsBeforeTheInterpreter() = runTest {
        port = null
        val server = server()
        server.start()
        runCurrent()
        assertEquals(LocalState.Failed(LocalServer.NO_PORT), server.state)
        assertFalse(runtime.started)
        assertNull(server.url)
    }

    @Test
    fun aBuildWithoutTheWebAppSaysSo() = runTest {
        val empty = WebAssets({ throw FileNotFoundException(it) }, File(folder.root, "files/frontend_dist"))
        val server = server(webApp = empty)
        server.start()
        runCurrent()
        assertEquals(LocalState.Failed(LocalServer.NO_WEB_APP), server.state)
        assertEquals("no port is picked", emptyList<Int>(), asked)
    }

    @Test
    fun anInterpreterThatRanAlreadyFails() = runTest {
        runtime.startable = false
        val server = server()
        server.start()
        runCurrent()
        assertEquals(LocalState.Failed(LocalServer.ALREADY_RAN), server.state)
    }

    @Test
    fun theRuntimesExitFailsTheServer() = runTest {
        val server = server()
        server.start()
        runCurrent()
        runtime.exit!!("The server stopped with an error.")
        runCurrent()
        assertEquals(LocalState.Failed("The server stopped with an error."), server.state)
    }

    @Test
    fun tryAgainAsksTheSupervisorOnTheSamePort() = runTest {
        val server = server()
        server.start()
        runCurrent()
        write("stopped", "the server could not listen on its port; the log says why")
        advance(250)
        // gamma_mobile.restart writes "starting" before it returns.
        runtime.onRestart = { write("starting", "") }
        server.restart()
        assertEquals(LocalState.Starting, server.state)
        runCurrent()
        assertEquals(1, runtime.restarts)
        assertEquals("one interpreter", 1, runtime.environments.size)
        advance(1_000)
        assertEquals("not the old failure", LocalState.Starting, server.state)
        up = true
        advance(250)
        assertEquals(LocalState.Running("http://127.0.0.1:50123"), server.state)
    }

    @Test
    fun aRestartWithoutAnInterpreterFails() = runTest {
        val server = server()
        server.start()
        runCurrent()
        runtime.exit!!("The server stopped with an error.")
        runCurrent()
        runtime.restartable = false
        server.restart()
        runCurrent()
        assertEquals(LocalState.Failed(LocalServer.NOT_RUNNING), server.state)
    }

    @Test
    fun aStartThatFailedBeforeTheInterpreterStartsOverOnRestart() = runTest {
        port = null
        val server = server()
        server.start()
        runCurrent()
        port = 50124
        server.restart()
        assertEquals(LocalState.Starting, server.state)
        runCurrent()
        assertEquals(0, runtime.restarts)
        assertEquals("50124", runtime.environments.single()["GAMMA_PORT"])
        assertEquals("http://127.0.0.1:50124", server.url)
    }

    @Test
    fun aRestartWhileStartingIsIgnored() = runTest {
        val server = server()
        server.start()
        runCurrent()
        server.restart()
        runCurrent()
        assertEquals(0, runtime.restarts)
        assertEquals(1, runtime.environments.size)
    }

    @Test
    fun aRecheckRestartsAServerThatNoLongerAnswers() = runTest {
        val server = server()
        server.start()
        runCurrent()
        up = true
        advance(250)
        up = false
        val probesThen = probes
        server.recheck()
        server.recheck() // one at a time
        runCurrent()
        assertEquals(1, probes - probesThen)
        advance(1_000)
        assertEquals(2, probes - probesThen)
        assertEquals(0, runtime.restarts)
        advance(1_000)
        assertEquals("three tries, a second apart, then the restart", 1, runtime.restarts)
        assertEquals(LocalState.Starting, server.state)
        up = true
        advance(250)
        assertEquals(LocalState.Running("http://127.0.0.1:50123"), server.state)
    }

    @Test
    fun aRecheckOfAServerThatAnswersChangesNothing() = runTest {
        val server = server()
        server.start()
        runCurrent()
        up = true
        advance(250)
        server.recheck()
        advance(5_000)
        assertEquals(0, runtime.restarts)
        assertEquals(LocalState.Running("http://127.0.0.1:50123"), server.state)
    }

    @Test
    fun aRecheckIsOnlyForARunningServer() = runTest {
        val server = server()
        server.recheck()
        runCurrent()
        assertEquals(0, probes)
        assertEquals(LocalState.Stopped, server.state)
    }

    @Test
    fun theLogsTailIsItsLast4096BytesTrimmed() = runTest {
        val server = server()
        assertNull("no log yet", server.logTail())
        server.logFile.parentFile!!.mkdirs()
        server.logFile.writeText("   \n\n")
        assertNull("nothing but space", server.logTail())
        server.logFile.writeText("x".repeat(5000) + "\nthe last line\n\n")
        val tail = server.logTail()!!
        assertTrue(tail.length <= 4096)
        assertTrue(tail.startsWith("xxx"))
        assertTrue(tail.endsWith("the last line"))
    }

    @Test
    fun theDataSizeCountsEveryFile() = runTest {
        File(data, "workspaces/w1").mkdirs()
        File(data, "users.db").writeBytes(ByteArray(1000))
        File(data, "workspaces/w1/pages.db").writeBytes(ByteArray(234))
        val server = server()
        assertEquals(1234L, server.dataSize())
    }
}
