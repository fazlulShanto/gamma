package com.gammapdf.gamma.app

import com.gammapdf.gamma.shell.Origin
import com.gammapdf.gamma.shell.UrlParts

/**
 * What the chrome pages (desktop/ui/bar.html and launcher.html) read of the
 * shell, built as the desktop builds its barState and fullState
 * (desktop/main.js) and the iPad its own (ipad/GammaIPad/App/ShellState.swift),
 * with only what the app has: no `keeping`, `update` or `settings`, so the
 * pages hide the sync button, the update pill and the Settings block. One key
 * is the apps' own, `barHideable`, which shows the bar's hide button. Pure,
 * over plain values; every value in it is one JSON carries. `current`, `busy`
 * and `gamma` are JSON null when unset; `lastOpened`, the local card's `url`
 * and `sizeBytes`, and a remote's `reachable` are left out.
 */
object ShellState {
    /** This device's data folder, as the launcher's card names it. */
    const val DATA_DIR = "App storage › Gamma › local"

    /** What the credentials show when this device keeps no password for the account. */
    const val NO_PASSWORD = "This device keeps no password for this account. A password the server made itself is in its log."

    /**
     * [session] is what the page's server answered `/api/session`
     * (shell/Session.kt), read only while [page], the shell's address, is on
     * the chosen server; [sizeBytes] is this device's data folder, which only
     * the launcher asks for; [barHideable] is whether the bar's hide button
     * would hide it now (!AppModel.barNeeded).
     */
    fun make(
        version: String,
        theme: String,
        servers: SavedServers,
        reachable: Map<String, Boolean>,
        local: LocalState,
        slowStart: Boolean,
        login: Credentials?,
        session: Map<String, Any?>?,
        page: String?,
        barHideable: Boolean,
        sizeBytes: Long? = null,
    ): Map<String, Any?> {
        val localUrl = (local as? LocalState.Running)?.url
        val state = linkedMapOf<String, Any?>(
            "version" to version, "theme" to theme, "current" to null, "busy" to null, "gamma" to null,
            "barHideable" to barHideable,
        )

        val current = servers.current
        var shown: String? = null
        if (current != null) {
            state["current"] = mapOf("id" to current.id, "name" to servers.name(current),
                                     "type" to if (current == Server.Local) "local" else "remote")
            state["lastOpened"] = current.id
            shown = when (current) {
                Server.Local -> localUrl
                is Server.Remote -> current.url
            }
        }
        if (current == Server.Local && local == LocalState.Starting) {
            val name = servers.name(Server.Local)
            state["busy"] = if (slowStart) {
                "Still starting $name… the first start after an install or an update can take a few minutes"
            } else {
                "Starting $name…"
            }
        }
        // The workspaces of the server the page is on, as it sent them, and
        // the one the page shows (its ?ws=), else the account's default.
        val user = session?.get("user")
        val origin = Origin.of(shown)
        if (session != null && user != null && page != null && origin != null && Origin.of(page) == origin) {
            state["gamma"] = mapOf(
                "user" to user,
                "list" to (session["workspaces"] ?: emptyList<Any?>()),
                "current" to (UrlParts.parse(page)?.queryValue("ws") ?: session["default_workspace"]),
            )
        }

        val thisDevice = linkedMapOf<String, Any?>(
            "id" to Server.Local.id, "name" to servers.name(Server.Local), "type" to "local",
            "running" to (localUrl != null), "dataDir" to DATA_DIR,
            "adminUser" to (login?.username ?: Credentials.ACCOUNT),
            "adminPassword" to (login?.password ?: NO_PASSWORD),
        )
        if (localUrl != null) thisDevice["url"] = localUrl
        if (sizeBytes != null) thisDevice["sizeBytes"] = sizeBytes
        val others = servers.remotes.map { url ->
            val remote = Server.Remote(url)
            linkedMapOf<String, Any?>("id" to remote.id, "name" to servers.name(remote), "type" to "remote", "url" to url)
                .also { entry -> reachable[url]?.let { entry["reachable"] = it } }
        }
        state["servers"] = listOf(thisDevice) + others
        return state
    }
}
