package com.gammapdf.gamma.server

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import com.gammapdf.gamma.json.Json

/**
 * Chaquopy's CPython 3.13 running Gamma's FastAPI backend for the process's
 * life (docs/dev/android.md "The local server"), as
 * ipad/GammaIPad/Server/PythonRuntime.swift runs the iPad's. One
 * interpreter, started once on a thread of its own and never finalized:
 * `gamma_mobile.run(env)` (backend/gamma_mobile.py) runs uvicorn under a
 * supervisor and never returns. An interpreter cannot be started a second
 * time in one process, but the server inside it can: [restart] asks the
 * supervisor to run it again (after a failed start, or a server that
 * stopped answering), on the same port.
 *
 * The state is the process's, in the companion, whichever instance started
 * the interpreter: the app's LocalServer, or the device test's.
 */
class PythonRuntime(context: Context) : LocalServer.Runtime {
    private val context: Context = context.applicationContext

    override val started: Boolean get() = Companion.started

    override fun start(environment: Map<String, String>, log: File, onExit: (String) -> Unit): Boolean =
        Companion.start(context, environment, log, onExit)

    override fun restart(): Boolean = Companion.restart()

    companion object {
        /**
         * The interpreter's thread: Python's main thread, which runs its
         * imports and the server's event loop. Android's default thread stack
         * is too small for that, as is iOS's; the iPad gives it the same.
         */
        const val STACK_SIZE = 16L shl 20

        private val begun = AtomicBoolean(false)

        /** Whether the interpreter is up and the entry still runs: what [restart] needs to know. */
        @Volatile private var interpreterUp = false
        @Volatile private var entryExited = false

        /** Read by the device test, which can start the server only when the app did not. */
        val started: Boolean get() = begun.get()

        /**
         * Starts the interpreter on its own thread, then the entry with
         * [environment] (gamma.config reads it at import, so gamma_mobile
         * makes it the process's before anything imports gamma). [onExit]
         * hears why the server stopped, which it only does on a failure.
         * False when it was started already.
         */
        fun start(context: Context, environment: Map<String, String>, log: File, onExit: (String) -> Unit): Boolean {
            if (!begun.compareAndSet(false, true)) return false
            val env = Json.write(environment, sortKeys = true)
            Thread(null, { onExit(run(context, env, log)) }, "gamma-server", STACK_SIZE).start()
            return true
        }

        /**
         * Asks the server to run again in the interpreter it has
         * (`gamma_mobile.restart`: a server still running is stopped, and a
         * new one listens on the same port), from any thread but the
         * interpreter's own. False when there is nothing to restart: the
         * interpreter never came up, or the entry itself ended (an import
         * error), which only a new build fixes. Chaquopy takes the GIL for
         * the call, so it waits for the server's thread to yield it, which
         * uvicorn's loop does at every tick.
         */
        fun restart(): Boolean {
            if (!interpreterUp || entryExited) return false
            return try {
                Python.getInstance().getModule("gamma_mobile").callAttr("restart")
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Starts the interpreter, then runs the server. Returns only when something failed, with what to show. */
        private fun run(context: Context, environment: String, log: File): String {
            // The first start after an install or an update unpacks the
            // standard library and the packages here, off the main thread:
            // the slow start the bar tells about.
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            } catch (error: Throwable) {
                note(log, "Python could not start", error)
                return "Python could not start: ${error.message ?: error.javaClass.simpleName}"
            }
            interpreterUp = true
            // Blocks while the supervisor runs, which is for good unless the
            // entry itself fails; gamma_mobile writes the traceback of that to
            // the log, and the note below adds what Java saw.
            return try {
                Python.getInstance().getModule("gamma_mobile").callAttr("run", environment)
                "The server stopped."
            } catch (error: Throwable) {
                note(log, "the server's entry ended", error)
                "The server stopped with an error."
            } finally {
                entryExited = true
            }
        }

        /**
         * What failed, appended to the server's log: the iPad points the
         * process's output at the log before Python starts, and what fails
         * here, before gamma_mobile redirects Python's, would otherwise reach
         * only logcat.
         */
        private fun note(log: File, what: String, error: Throwable) {
            try {
                log.parentFile?.mkdirs()
                log.appendText("\n--- ${Instant.now()} $what ---\n${error.stackTraceToString()}\n")
            } catch (e: IOException) {
                // The log is advice; the reason still reaches the launcher.
            }
        }
    }
}
