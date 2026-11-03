package com.gammapdf.gamma.server

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import com.gammapdf.gamma.BuildConfig
import com.gammapdf.gamma.app.Credentials
import com.gammapdf.gamma.app.Prefs

/**
 * The local server on this device: its data in the app's internal storage
 * (filesDir/Gamma/local, which no other app reads, on a filesystem SQLite's
 * WAL trusts; the iPad's is Documents/Gamma/local), the web app unpacked
 * into filesDir/frontend_dist, its temporary files in cacheDir/gamma-tmp,
 * the password in the keystore and the interpreter Chaquopy's. [scope] is
 * the application's main-thread scope. The device test passes a data
 * directory, a vault and a seed of its own.
 */
fun LocalServer.Companion.onDevice(
    context: Context,
    prefs: Prefs,
    scope: CoroutineScope,
    dataDirectory: File = File(context.filesDir, "Gamma/local"),
    vault: PasswordVault = KeystoreVault(context),
    seed: Credentials? = null,
): LocalServer {
    val app = context.applicationContext
    return LocalServer(
        dataDirectory = dataDirectory,
        webApp = WebAssets({ app.assets.open(it) }, File(app.filesDir, WebAssets.ROOT)),
        tempDirectory = File(app.cacheDir, "gamma-tmp"),
        version = BuildConfig.VERSION_NAME,
        prefs = prefs,
        vault = vault,
        runtime = PythonRuntime(app),
        scope = scope,
        seed = seed,
    )
}
