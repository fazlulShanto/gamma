package com.gammapdf.gamma.app

import com.gammapdf.gamma.json.Json
import com.gammapdf.gamma.shell.UrlParts

/**
 * A Gamma server the shell can show: the one inside the app ("This device",
 * LocalHost) or another one at its address (ServerAddress.parse's origin).
 */
sealed interface Server {
    /** The id the chrome pages name it by, as the desktop's registry has ids: "local", or a remote's origin. */
    val id: String

    data object Local : Server {
        override val id: String get() = "local"
    }

    data class Remote(val url: String) : Server {
        override val id: String get() = url
    }

    companion object {
        /** The server an id names; null for a blank one. */
        fun of(id: String): Server? = when {
            id == Local.id -> Local
            id.isBlank() -> null
            else -> Remote(id)
        }
    }
}

/**
 * What is kept between launches, as JSON under one key
 * (ipad/GammaIPad/App/AppModel.swift SavedServers):
 * `{current?: <server id>, remotes: [<origin>…], names?: {<server id>: <name>}}`.
 */
data class SavedServers(val current: Server?, val remotes: List<String>, val names: Map<String, String>? = null) {
    /** A server's name: the one given it, else "This device", or a remote's host with its port when it has one. */
    fun name(of: Server): String {
        names?.get(of.id)?.let { return it }
        return when (of) {
            Server.Local -> LOCAL_NAME
            is Server.Remote -> {
                val parts = UrlParts.parse(of.url)
                val host = parts?.host?.takeIf { it.isNotEmpty() } ?: return of.url
                val shown = if (':' in host) "[$host]" else host
                parts.port?.let { "$shown:$it" } ?: shown
            }
        }
    }

    val json: String
        get() {
            val out = LinkedHashMap<String, Any?>()
            current?.let { out["current"] = it.id }
            out["remotes"] = remotes
            if (!names.isNullOrEmpty()) out["names"] = names
            return Json.write(out)
        }

    fun save(prefs: Prefs) = prefs.putString(KEY, json)

    companion object {
        const val KEY = "gamma.servers"
        const val LOCAL_NAME = "This device"

        /** What was kept, or nothing listed when nothing was (or it cannot be read). */
        fun load(prefs: Prefs): SavedServers {
            val saved = Json.objectOrNull(prefs.string(KEY)) ?: return SavedServers(null, emptyList())
            val remotes = (saved["remotes"] as? List<*>)?.filterIsInstance<String>()?.distinct() ?: emptyList()
            val names = (saved["names"] as? Map<*, *>)
                ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }?.toMap()
            return SavedServers((saved["current"] as? String)?.let(Server::of), remotes, names?.ifEmpty { null })
        }
    }
}
