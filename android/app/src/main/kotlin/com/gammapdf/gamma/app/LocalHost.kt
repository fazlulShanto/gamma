package com.gammapdf.gamma.app

/** The local server's state (server/LocalServer.kt), as the shell follows it. */
sealed interface LocalState {
    data object Stopped : LocalState
    data object Starting : LocalState

    /** Answering at [url], "http://127.0.0.1:<port>". */
    data class Running(val url: String) : LocalState

    /** Did not start, or stopped; [message] says why. */
    data class Failed(val message: String) : LocalState
}

/** The local server's seeded account, which the shell signs in with silently. */
data class Credentials(val username: String, val password: String) {
    companion object {
        /** The account a fresh data directory is seeded with. */
        const val ACCOUNT = "admin"
    }
}

/**
 * Gamma's own server inside the app, "This device" (server/LocalServer.kt,
 * ported from ipad/GammaIPad/Server/LocalServer.swift), as AppModel and the
 * shell see it. Main-thread confined.
 */
interface LocalHost {
    val state: LocalState

    /** Still starting after a minute: the bar says it can take a while. */
    val slowStart: Boolean

    /** "http://127.0.0.1:<port>" once the port is known. */
    val url: String?

    /** The seeded account, once started; null when this device keeps no password for it. */
    val login: Credentials?

    /** Called on the main thread on any change of the above. */
    var onChange: (() -> Unit)?

    /** Starts the server unless it was started already. */
    fun start()

    /** The server again: after a failed start (the launcher's Try again), or when it stopped answering. */
    fun restart()

    /** The app is in front again: a running server that no longer answers is restarted. */
    fun recheck()

    /** The last 4096 bytes of server.log, trimmed; null when there is no log. */
    fun logTail(): String?

    /** The data directory's size on disk, counted off the main thread. */
    suspend fun dataSize(): Long
}
