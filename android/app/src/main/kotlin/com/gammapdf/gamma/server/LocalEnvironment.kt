package com.gammapdf.gamma.server

import com.gammapdf.gamma.app.Credentials

/**
 * The server's environment, which gamma/config.py reads at import and
 * backend/gamma_mobile.py's run() makes the process's own
 * (ipad/GammaIPad/Server/LocalServer.swift LocalEnvironment). gamma_mobile
 * adds the apps' defaults under it: GAMMA_UPDATES=app and
 * GAMMA_UPDATE_CHECK=off, the app bringing the server's updates.
 */
object LocalEnvironment {
    const val CLOUD_ISSUER = "https://account.gammapdf.com"

    fun make(
        dataDirectory: String,
        staticDirectory: String,
        tempDirectory: String,
        version: String,
        port: Int,
        seed: Credentials?,
    ): Map<String, String> {
        val environment = linkedMapOf(
            "GAMMA_DATA_DIR" to dataDirectory,
            // The built web app the server serves at its root.
            "GAMMA_STATIC_DIR" to staticDirectory,
            // The admin dashboard reports the app the server ships in (gamma/version.py).
            "GAMMA_VERSION" to version,
            // Gamma Cloud sign-in is on until the admin picks another account
            // server or none, as on the desktop (gamma/cloud_auth.py).
            "GAMMA_CLOUD_DEFAULT_ISSUER" to CLOUD_ISSUER,
            // Read by backend/gamma_mobile.py.
            "GAMMA_PORT" to port.toString(),
            // iOS gives an app its own temporary folder; Android gives none,
            // and Python's tempfile (exports, imports, backups) would fall
            // back to the root.
            "TMPDIR" to tempDirectory,
        )
        // Only for the first start: the server seeds this account on an empty
        // data directory and ignores these afterwards.
        if (seed != null) {
            environment["GAMMA_ADMIN_USER"] = seed.username
            environment["GAMMA_ADMIN_PASSWORD"] = seed.password
        }
        return environment
    }
}
