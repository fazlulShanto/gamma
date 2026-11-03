package com.gammapdf.gamma.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.gammapdf.gamma.shell.ShellFailure

/**
 * The app's one piece of state, ported from ipad/GammaIPad/App/AppModel.swift:
 * which server the shell shows, the servers the chrome lists (the desktop's
 * bar and launcher), as the desktop's registry has them
 * (desktop/lib/registry.js), whether the launcher is up, and whether the bar
 * is. The session is the WebView's cookie and the library is the server's:
 * the shell never holds a token (docs/dev/android.md "The shell").
 *
 * Main-thread confined. [onChange] runs after every change, and the shell
 * follows it (ShellController): the page's address, the launcher, the bar
 * and the chrome's state. [probe] asks a server's /api/health (off the main
 * thread, inside it) on [scope]; [now] is the clock in milliseconds.
 */
class AppModel(
    private val prefs: Prefs,
    val local: LocalHost,
    probe: suspend (String) -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * One showing of the launcher: each is a fresh page load, so a failure
     * repeated with the same text still loads it again (two are never
     * equal). [error] is the failure it shows (ShellFailure), as the
     * desktop's `?error=`.
     */
    class Launcher(val error: String? = null)

    private val health = probe

    /** The server chosen last; null until the first one is opened. */
    var current: Server?
        private set

    /** The other servers, in the order they were added. */
    var remotes: List<String>
        private set

    /** The names given to servers, by id; a server without one is named by SavedServers.name. */
    var names: Map<String, String>
        private set

    /**
     * The address the shell shows. It changes only to another address, so
     * the page's WebView stays while the local server starts.
     */
    var shellUrl: String? = null
        private set

    /** How many times a server was opened, so the shell reloads even the server already shown (a retry). */
    var connects = 0
        private set

    /** The remotes' last /api/health answers (the chrome's dots). */
    var reachable: Map<String, Boolean> = emptyMap()
        private set

    /** The shell's last main-frame load failed with this message; cleared when a page arrives. */
    var loadError: String? = null
        private set

    /** The launcher over the page, null when it is closed. */
    var launcher: Launcher? = null
        private set

    /**
     * The bar hidden by the person (its button, Ctrl+Shift+B, the page's
     * View menu), kept between launches. It still shows while it is needed.
     */
    var barHidden: Boolean
        private set

    var onChange: (() -> Unit)? = null

    private val probed = HashMap<String, Long>()
    private val asking = HashMap<String, Any>()
    private var localSeen: LocalState

    init {
        val saved = SavedServers.load(prefs)
        current = saved.current
        remotes = saved.remotes
        names = saved.names ?: emptyMap()
        barHidden = prefs.bool(BAR_HIDDEN_KEY, false)
        (saved.current as? Server.Remote)?.let { shellUrl = it.url }
        // The first launch, or the server chosen last was removed: the launcher, over an empty shell.
        if (saved.current == null) launcher = Launcher()
        localSeen = local.state
        local.onChange = { localChanged() }
        if (saved.current == Server.Local) local.start()
    }

    /** What is kept between launches, as it stands. */
    val saved: SavedServers get() = SavedServers(current, remotes, names.ifEmpty { null })

    /** The bar shows unless the person hid it, and while it is needed. */
    val barShown: Boolean get() = !barHidden || barNeeded

    /**
     * The bar is needed while the launcher is up, which its mark closes, and
     * while this device starts, which its status says: the page under it is
     * empty meanwhile. Its hide button is offered only when it is not.
     */
    val barNeeded: Boolean get() = launcher != null || (current == Server.Local && local.state == LocalState.Starting)

    /** The local server's address while it answers. */
    private val localUrl: String? get() = (local.state as? LocalState.Running)?.url

    /**
     * The server already shown, loaded and up, only closes the launcher. Any
     * other is shown, the same one again too (a retry). This device not
     * running is started, or started again after a failure, and the launcher
     * stays up until it runs. Opening never lists a server.
     */
    fun open(server: Server) {
        val running = server != Server.Local || localUrl != null
        if (server == current && loadError == null && running) {
            launcher = null
            changed()
            return
        }
        current = server
        save()
        when (server) {
            is Server.Remote -> show(server.url)
            Server.Local -> {
                val url = localUrl
                if (url != null) show(url) else local.restart()
            }
        }
        changed()
    }

    /** The bar's hide button, Ctrl+Shift+B and the page's View menu. */
    fun setBarHidden(hidden: Boolean) {
        if (hidden == barHidden) return
        barHidden = hidden
        prefs.putBool(BAR_HIDDEN_KEY, hidden)
        changed()
    }

    /** The mark, *All servers…* and Ctrl+Shift+L: a launcher up without an error closes; otherwise a fresh one opens. */
    fun toggleLauncher() {
        launcher = if (launcher != null && launcher?.error == null) null else Launcher()
        changed()
    }

    /** The shell's main-frame load failed: the launcher shows why, with Try again (`open`). */
    fun loadFailed(message: String) {
        loadError = message
        val current = current
        val url = shellUrl
        if (current != null && url != null) launcher = Launcher(ShellFailure.unreachable(url, current.id, message).json)
        changed()
    }

    /** A page arrived: a load error is over, and the launcher showing it closes. One opened by hand stays. */
    fun pageLoaded() {
        if (loadError == null) return
        loadError = null
        if (launcher?.error != null) launcher = null
        changed()
    }

    /** The seeded account for [url] when it is the local server's, so the shell signs in there and nowhere else. */
    fun login(forUrl: String?): Credentials? = if (forUrl != null && forUrl == localUrl) local.login else null

    /** Adds a server to the end of the list without opening it. */
    fun add(url: String) {
        if (url in remotes) return
        remotes = remotes + url
        save()
        probe(listOf(url), force = true)
        changed()
    }

    /** A server's address edited: the one shown loads its new address, and its name goes with it. */
    fun replace(old: String, new: String) {
        val index = remotes.indexOf(old)
        if (old == new || index < 0) return
        remotes = if (new in remotes) remotes.filterIndexed { i, _ -> i != index } else remotes.toMutableList().also { it[index] = new }
        names[Server.Remote(old).id]?.let { names = names - Server.Remote(old).id + (Server.Remote(new).id to it) }
        forget(old)
        if (current == Server.Remote(old)) {
            current = Server.Remote(new)
            shellUrl = new
        }
        save()
        probe(listOf(new), force = true)
        changed()
    }

    /**
     * Takes a server off the list. The current one is unchosen, as on the
     * desktop: its page stays under the launcher until another is opened, and
     * the next launch shows the launcher.
     */
    fun remove(url: String) {
        remotes = remotes.filter { it != url }
        names = names - Server.Remote(url).id
        forget(url)
        if (current == Server.Remote(url)) current = null
        save()
        changed()
    }

    /** Names a server; a blank name gives it back its own (SavedServers.name). */
    fun rename(server: Server, name: String) {
        val trimmed = name.trim()
        names = if (trimmed.isEmpty()) names - server.id else names + (server.id to trimmed)
        save()
        changed()
    }

    /**
     * Asks each server's /api/health, unless it was asked within the last
     * [PROBE_INTERVAL_MS]. Only the latest ask's answer for a server is kept,
     * and none for a server taken off the list meanwhile.
     */
    fun probe(urls: List<String>, force: Boolean = false) {
        val time = now()
        for (url in urls) {
            val last = probed[url]
            if (!force && last != null && time - last < PROBE_INTERVAL_MS) continue
            probed[url] = time
            val ask = Any()
            asking[url] = ask
            scope.launch {
                val up = try {
                    health(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (asking[url] !== ask) return@launch
                asking.remove(url)
                reachable = reachable + (url to up)
                changed()
            }
        }
    }

    /**
     * This device, while it is the server chosen: shown once it runs, which
     * closes the launcher; a failed start goes to the launcher with the end
     * of its log. Any change of the local server is the shell's to follow.
     */
    private fun localChanged() {
        val state = local.state
        if (state != localSeen) {
            localSeen = state
            if (current == Server.Local) {
                when (state) {
                    is LocalState.Running -> {
                        shellUrl = state.url
                        launcher = null
                    }
                    is LocalState.Failed ->
                        launcher = Launcher(ShellFailure.localFailed(state.message, local.logTail().orEmpty()).json)
                    LocalState.Starting, LocalState.Stopped -> {}
                }
            }
        }
        changed()
    }

    private fun show(url: String) {
        launcher = null
        shellUrl = url
        connects += 1
    }

    private fun forget(url: String) {
        reachable = reachable - url
        probed.remove(url)
        asking.remove(url)
    }

    private fun save() = saved.save(prefs)

    private fun changed() {
        onChange?.invoke()
    }

    companion object {
        /** Where `barHidden` is kept, beside the servers (SavedServers.KEY). */
        const val BAR_HIDDEN_KEY = "gamma.barHidden"

        /** A dot stays as it is this long before the chrome asks again, as the desktop caches its remotes' health. */
        const val PROBE_INTERVAL_MS = 30_000L
    }
}
