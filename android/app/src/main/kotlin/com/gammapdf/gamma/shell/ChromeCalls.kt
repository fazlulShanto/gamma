package com.gammapdf.gamma.shell

import com.gammapdf.gamma.app.AppModel
import com.gammapdf.gamma.app.LocalState
import com.gammapdf.gamma.app.Server
import com.gammapdf.gamma.app.ServerAddress
import com.gammapdf.gamma.app.ShellState

/**
 * What the chrome's calls reach beyond the model: the page's WebView, the
 * bar's view and the system (ShellController). Main-thread confined.
 */
interface ChromeHost {
    /** The app's versionName, which the launcher's footer shows. */
    val version: String

    /** The page's theme name (`<html data-theme>`), "" until it reported one; the chrome paints in it. */
    val themeName: String

    /** What the page's server answered /api/session (Session), null while no one is signed in or it is not known. */
    val session: Map<String, Any?>?

    /** The page's address now, which says the workspace it shows (`?ws=`). */
    val address: String?

    /** Asks the page's server for its session again; the answer comes as a state push. */
    fun readSession()

    /** The server shown, on one of its workspaces. */
    fun openWorkspace(id: String)

    /** The page again, or the server's address when no page of it is shown. */
    fun reload()

    /** The bar's view over the whole content while a dropdown is open, else its strip. */
    fun expandBar(on: Boolean)

    /** An open dropdown closes in the bar's page too, and the view goes back to its strip. */
    fun closeBarMenu()

    /** The launcher's Open data folder: this device's data, as far as Android shows it (the app's settings page). */
    fun revealData()

    /** The launcher's Show server log and Show log file: the log, shared. */
    fun revealLog()

    /** Puts [text] on the clipboard. */
    fun copy(text: String)
}

/**
 * The chrome pages' `gammaShell` calls (desktop/ui/bar.html and
 * launcher.html over assets/shell/chrome.js), answered as desktop/main.js
 * answers them, with what the app has: ported from ShellController.call in
 * ipad/GammaIPad/Shell/ShellView.swift (docs/dev/android.md "The chrome").
 * The desktop's other methods (keeping, folders, settings, updates) do not
 * exist here; only controls the touch shells hide call them.
 */
class ChromeCalls(private val model: AppModel, private val host: ChromeHost) {
    /** The state the chrome pages render, with this device's data size when the launcher asked for it. */
    fun state(sizeBytes: Long? = null): Map<String, Any?> = ShellState.make(
        version = host.version,
        theme = host.themeName,
        servers = model.saved,
        reachable = model.reachable,
        local = model.local.state,
        slowStart = model.local.slowStart,
        login = model.local.login,
        session = host.session,
        page = host.address,
        barHideable = !model.barNeeded,
        sizeBytes = sizeBytes,
    )

    /** The mark, *All servers…* and Ctrl+Shift+L: the bar's dropdown closes first. */
    fun toggleLauncher() {
        host.closeBarMenu()
        model.toggleLauncher()
    }

    /**
     * One call: its reply's value (JSON values only, null for none), or a
     * [Refusal] whose sentence the page's promise rejects with.
     */
    suspend fun call(method: String, args: List<Any?>): Any? {
        fun refuse(): Nothing = throw Refusal("Gamma for Android cannot read this call to $method.")
        fun string(index: Int): String = args.getOrNull(index) as? String ?: refuse()
        fun flag(index: Int): Boolean = args.getOrNull(index) as? Boolean ?: refuse()

        // A listed server, by its id.
        fun server(index: Int): Server {
            val id = string(index)
            return when (val server = Server.of(id)) {
                Server.Local -> server
                is Server.Remote -> if (server.url in model.remotes) server else throw Refusal("$id is not a listed server.")
                null -> throw Refusal("$id is not a listed server.")
            }
        }

        when (method) {
            "state" -> {
                model.probe(model.remotes)
                host.readSession()
                return state()
            }
            // No session read: the launcher lists on every push, and a read pushes.
            "list" -> {
                model.probe(model.remotes)
                return state(model.local.dataSize())
            }
            "open" -> model.open(server(0))
            "openWorkspace" -> {
                val id = string(0)
                host.expandBar(false)
                host.openWorkspace(id)
            }
            "launcher" -> toggleLauncher()
            "reload" -> host.reload()
            "barExpand" -> host.expandBar(flag(0))
            "hideBar" -> model.setBarHidden(true)
            "addRemote" -> {
                val name = string(0)
                val url = ServerAddress.parse(string(1)) ?: throw Refusal(NOT_AN_ADDRESS)
                if (url in model.remotes) throw Refusal("$url is already listed")
                model.add(url)
                model.rename(Server.Remote(url), name)
            }
            "rename" -> model.rename(server(0), string(1))
            "setUrl" -> {
                val old = server(0) as? Server.Remote ?: throw Refusal("This device's address is its own.")
                val new = ServerAddress.parse(string(1)) ?: throw Refusal(NOT_AN_ADDRESS)
                model.replace(old.url, new)
            }
            "remove" -> {
                val gone = server(0) as? Server.Remote ?: throw Refusal("This device cannot be removed.")
                model.remove(gone.url)
            }
            "revealData" -> host.revealData()
            "revealLog" -> host.revealLog()
            // The address only while the server answers there.
            "copyAddress" -> {
                val address = (model.local.state as? LocalState.Running)?.url ?: return null
                host.copy(address)
                return address
            }
            else -> throw Refusal("Gamma for Android has no $method.")
        }
        return null
    }

    companion object {
        const val NOT_AN_ADDRESS = "That is not a web address."
    }
}
