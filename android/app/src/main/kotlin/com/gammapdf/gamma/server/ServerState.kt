package com.gammapdf.gamma.server

import java.io.File
import java.io.IOException
import com.gammapdf.gamma.json.Json

/**
 * What the supervisor in backend/gamma_mobile.py last wrote to
 * `logs/server.state`: "starting", or "stopped" with why (a port it could
 * not listen on, a server that ended). Read while the app waits for
 * /api/health, so a start that failed is reported, with Try again, instead
 * of polled for ever (ipad/GammaIPad/Server/LocalServer.swift ServerState).
 */
data class ServerState(val state: String, val detail: String) {
    companion object {
        /** The file's state, or null: no file, or not the whole of one. */
        fun read(file: File): ServerState? {
            val text = try {
                file.readText()
            } catch (e: IOException) {
                return null
            }
            val json = Json.objectOrNull(text) ?: return null
            val state = json["state"] as? String ?: return null
            val detail = json["detail"] as? String ?: return null
            return ServerState(state, detail)
        }
    }
}
