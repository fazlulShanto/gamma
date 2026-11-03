package com.gammapdf.gamma.server

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gammapdf.gamma.app.Credentials
import com.gammapdf.gamma.app.LocalHost
import com.gammapdf.gamma.app.LocalState
import com.gammapdf.gamma.app.Prefs

/**
 * The local server, "This device": Gamma's own backend inside the app on
 * 127.0.0.1:<port>, as the desktop shell runs one beside it
 * (desktop/lib/sidecar.js) and as ipad/GammaIPad/Server/LocalServer.swift
 * runs the iPad's (docs/dev/android.md "The local server"). It owns nothing
 * the web app shows: it unpacks the web app, picks the port and the data
 * directory, seeds the first account, starts the interpreter ([Runtime],
 * PythonRuntime on the device) and says when the server answers.
 *
 * Main-thread confined: [scope] runs on the main thread, and the blocking
 * parts (files, sockets, the vault, the interpreter's restart, the health
 * probes) run on [io]. One per process, owned by the application, never by
 * an activity: the interpreter starts once per process. [LocalServer.onDevice]
 * (DeviceServer.kt) makes the app's.
 */
class LocalServer(
    /** The server's GAMMA_DATA_DIR, with its log and state file under logs/. */
    val dataDirectory: File,
    private val webApp: WebAssets,
    /** The server's TMPDIR, emptied before the interpreter starts. */
    private val tempDirectory: File,
    private val version: String,
    private val prefs: Prefs,
    private val vault: PasswordVault,
    private val runtime: Runtime,
    private val scope: CoroutineScope,
    /**
     * The account a first start seeds when the vault refuses the one made for
     * it. The app passes none; the device test passes one, as the iPad's
     * test host does for its unsigned Keychain. A data directory with
     * accounts already ignores it, as the server ignores its environment's.
     */
    private val seed: Credentials? = null,
    private val io: CoroutineContext = Dispatchers.IO,
    private val probe: (url: String, timeoutMs: Int) -> Boolean = Health::probe,
    private val pickPort: (remembered: Int) -> Int? = LocalPorts::choose,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : LocalHost {
    /** The interpreter, as LocalServer starts and restarts it. */
    interface Runtime {
        /** Whether this process started the interpreter already. */
        val started: Boolean

        /**
         * Starts the interpreter and the server's entry with [environment],
         * appending to [log] what fails before the server's own output does.
         * [onExit], on the server's thread, hears why it stopped, which it only
         * does on a failure. False when it was started already.
         */
        fun start(environment: Map<String, String>, log: File, onExit: (String) -> Unit): Boolean

        /** Asks the supervisor to run the server again; false when there is nothing to restart. Blocking. */
        fun restart(): Boolean
    }

    /** The server's own output (backend/gamma_mobile.py writes stdout and stderr here). */
    val logFile = File(dataDirectory, "logs/server.log")

    /** The supervisor's state (gamma_mobile.py STATE_FILE, [ServerState]). */
    val stateFile = File(dataDirectory, "logs/server.state")

    override var state: LocalState = LocalState.Stopped
        private set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    /** Still starting after a minute. The interpreter keeps going and the server is used once it answers. */
    override var slowStart = false
        private set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    /** The address the interpreter was started for, from then on; [restart] keeps it. */
    override var url: String? = null
        private set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    /** The seeded account the shell signs in with, once started; null when the vault has no password for it. */
    override var login: Credentials? = null
        private set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    override var onChange: (() -> Unit)? = null

    /** The start or restart in progress, whose wait a newer one replaces. */
    private var work: Job? = null
    private var rechecking = false

    /**
     * Starts the server unless it was started already. The interpreter runs
     * once per process ([Runtime]); the server in it can be run again
     * ([restart]).
     */
    override fun start() {
        if (state != LocalState.Stopped) return
        state = LocalState.Starting
        val remembered = prefs.int(PORT_KEY) ?: 0
        work?.cancel()
        work = scope.launch {
            when (val prepared = withContext(io) { prepare(remembered) }) {
                is Prepared.Failed -> state = LocalState.Failed(prepared.message)
                is Prepared.Ready -> launchServer(prepared)
            }
        }
    }

    /** Everything before the interpreter, off the main thread. */
    private fun prepare(remembered: Int): Prepared {
        try {
            Files.createDirectories(logFile.parentFile!!.toPath())
        } catch (e: Exception) {
            return Prepared.Failed("Gamma cannot make its data folder: ${describe(e)}")
        }
        stateFile.delete() // the last launch's; this start writes its own
        val staticDirectory = try {
            webApp.ensure()
        } catch (e: WebAssets.Missing) {
            return Prepared.Failed(NO_WEB_APP)
        } catch (e: IOException) {
            return Prepared.Failed("Gamma could not unpack its web app: ${describe(e)}")
        }
        // What an earlier process left in it (an export cut short by the
        // system ending the app); nothing uses it before the interpreter.
        if (!runtime.started) tempDirectory.deleteRecursively()
        try {
            Files.createDirectories(tempDirectory.toPath())
        } catch (e: Exception) {
            return Prepared.Failed("Gamma cannot make its temporary folder: ${describe(e)}")
        }
        val port = pickPort(remembered) ?: return Prepared.Failed(NO_PORT)

        // The first start on an empty data directory seeds this account: one
        // made for the vault, else the caller's (`seed`), else the server's
        // own, with a password only its log knows. Afterwards the server keeps
        // the accounts it has and ignores the seed, so only a password stored
        // for it is any use: a data directory without one (the vault's key
        // lost, as after a restore to another device) gets no silent sign-in
        // and the person signs in on the page.
        val fresh = !File(dataDirectory, "users.db").exists()
        val login = if (fresh) LocalLogin.stored(vault) ?: seed else LocalLogin.existing(vault)
        val environment = LocalEnvironment.make(
            dataDirectory = dataDirectory.path,
            staticDirectory = staticDirectory.path,
            tempDirectory = tempDirectory.path,
            version = version,
            port = port,
            seed = if (fresh) login else null,
        )
        return Prepared.Ready(port, login, environment)
    }

    private suspend fun launchServer(ready: Prepared.Ready) {
        prefs.putInt(PORT_KEY, ready.port)
        login = ready.login
        val started = runtime.start(ready.environment, logFile) { reason ->
            scope.launch { state = LocalState.Failed(reason) }
        }
        if (!started) {
            state = LocalState.Failed(ALREADY_RAN)
            return
        }
        val address = "http://127.0.0.1:${ready.port}"
        url = address
        waitUntilUp(address)
    }

    /**
     * The desktop's rule (sidecar.js start), every 250 ms, but with no
     * deadline: giving up would only hide a server that answers later. A
     * start that takes long (the first one after an install, which unpacks
     * Python and its packages) is marked slow after a minute and keeps being
     * polled, every second from then on. It fails when the supervisor says
     * the server stopped before it answered ([ServerState]: a port it could
     * not listen on, say), or on the runtime's own exit.
     */
    private suspend fun waitUntilUp(address: String) {
        val slowAfter = clock() + SLOW_AFTER_MS
        while (state == LocalState.Starting) {
            if (withContext(io) { probe(address, START_PROBE_MS) }) {
                if (state == LocalState.Starting) {
                    slowStart = false
                    state = LocalState.Running(address)
                }
                return
            }
            val written = withContext(io) { ServerState.read(stateFile) }
            if (written != null && written.state == "stopped") {
                if (state == LocalState.Starting) {
                    slowStart = false
                    state = LocalState.Failed(written.detail.ifEmpty { STOPPED_EARLY })
                }
                return
            }
            if (!slowStart && clock() > slowAfter) slowStart = true
            delay(if (slowStart) 1000 else 250)
        }
    }

    /**
     * Asked when the app is in front again (ProcessLifecycleOwner's start).
     * A server that no longer answers (the system may have frozen or starved
     * the process meanwhile) is run again in the same interpreter
     * ([restart]), the bar saying it starts for the moment that takes, rather
     * than left behind a page that cannot load.
     */
    override fun recheck() {
        val running = state as? LocalState.Running ?: return
        if (rechecking) return
        rechecking = true
        scope.launch {
            try {
                // A few tries: the server's thread is just waking up with the app.
                for (attempt in 0 until 3) {
                    if (attempt > 0) delay(1000)
                    if (withContext(io) { probe(running.url, RECHECK_PROBE_MS) }) return@launch
                }
                if (state == running) restart()
            } finally {
                rechecking = false
            }
        }
    }

    /**
     * The server again: after a failed start (the launcher's Try again) or
     * when it stopped answering ([recheck]). With the interpreter up,
     * `gamma_mobile.restart` stops a server still running and runs a new one
     * on the same port, so the address, and what the web app keeps per
     * origin, stay. A start that failed before the interpreter (no free port,
     * no data folder) is tried from the beginning; one the interpreter itself
     * could not survive stays failed until the app is opened again.
     */
    override fun restart() {
        if (state == LocalState.Starting) return
        val address = url
        if (address == null) {
            state = LocalState.Stopped
            start()
            return
        }
        state = LocalState.Starting
        slowStart = false
        work?.cancel()
        work = scope.launch {
            val asked = withContext(io) { runtime.restart() }
            if (!asked) {
                if (state == LocalState.Starting) state = LocalState.Failed(NOT_RUNNING)
                return@launch
            }
            waitUntilUp(address)
        }
    }

    /** The end of the server's log, for a failed start. */
    override fun logTail(): String? = tail(logFile, LOG_TAIL_BYTES)

    /** The data directory's size: the sum of its files' lengths, counted on [io]. */
    override suspend fun dataSize(): Long = withContext(io) {
        dataDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun changed() {
        onChange?.invoke()
    }

    private sealed interface Prepared {
        data class Ready(val port: Int, val login: Credentials?, val environment: Map<String, String>) : Prepared
        data class Failed(val message: String) : Prepared
    }

    companion object {
        /** The port the server had last time, kept as the iPad keeps it in UserDefaults. */
        const val PORT_KEY = "gamma.local.port"
        const val LOG_TAIL_BYTES = 4096
        const val SLOW_AFTER_MS = 60_000L
        const val START_PROBE_MS = 1500
        const val RECHECK_PROBE_MS = 2000

        const val NO_WEB_APP = "This build of Gamma has no web app in it (frontend/dist was not among its assets)."
        const val NO_PORT = "Gamma found no free port on this device."
        const val ALREADY_RAN = "The server already ran in this session. Close Gamma and open it again."
        const val STOPPED_EARLY = "The server stopped before it answered."
        const val NOT_RUNNING = "Python is not running in this app. Close Gamma and open it again."

        /** The last [maxBytes] of [file] as UTF-8 (a character cut in two read as a replacement), trimmed; null when there is nothing. */
        fun tail(file: File, maxBytes: Int): String? = try {
            RandomAccessFile(file, "r").use { handle ->
                val size = handle.length()
                val start = if (size > maxBytes) size - maxBytes else 0L
                val bytes = ByteArray((size - start).toInt())
                handle.seek(start)
                handle.readFully(bytes)
                String(bytes, Charsets.UTF_8).trim().ifEmpty { null }
            }
        } catch (e: IOException) {
            null
        }

        private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName
    }
}
