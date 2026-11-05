package com.gammapdf.gamma.app

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gammapdf.gamma.BuildConfig
import com.gammapdf.gamma.server.Health
import com.gammapdf.gamma.server.LocalServer
import com.gammapdf.gamma.server.onDevice
import com.gammapdf.gamma.shell.OpenedFiles
import com.gammapdf.gamma.shell.Saves

/**
 * Gamma for Android (docs/dev/android.md): a Gamma server's own web app in a
 * WebView, under the desktop app's own bar and launcher, with the pen
 * captured natively over it, and a Gamma server of its own inside the app
 * (LocalServer). The web app owns every page, tool and decision; the app is
 * a window, an input device, a preview layer and a process manager.
 *
 * The process holds what outlives an activity, as the iPad's GammaApp holds
 * its model (ipad/GammaIPad/App/GammaApp.swift): the model the shell follows,
 * the local server, whose interpreter runs once per process, the files
 * opened in the app until the page takes them, and the files being saved.
 * An activity made again finds them as they were (ShellController).
 */
class GammaApplication : Application() {
    /** The process's main-thread scope: the model's, the local server's and their work's. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: Prefs
        private set
    lateinit var local: LocalServer
        private set
    lateinit var opened: OpenedFiles
        private set
    lateinit var saves: Saves
        private set
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        // Chrome's inspector (chrome://inspect) for a debug build, as the iPad's debug build has Safari's.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        prefs = AndroidPrefs(this)
        opened = OpenedFiles(File(cacheDir, "Opened"))
        saves = Saves(File(cacheDir, "Saves"))
        local = LocalServer.onDevice(this, prefs, scope)
        model = AppModel(prefs, local, { url -> withContext(Dispatchers.IO) { Health.probe(url, PROBE_TIMEOUT_MS) } }, scope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Back from the background: the local server may have lost its socket meanwhile (LocalServer.recheck).
            override fun onStart(owner: LifecycleOwner) = local.recheck()

            // The session cookie on disk before the system may end the process.
            override fun onStop(owner: LifecycleOwner) {
                val cookies = CookieManager.getInstance()
                scope.launch(Dispatchers.IO) { cookies.flush() }
            }
        })
    }

    private companion object {
        /** How long the chrome's dots wait for a server's /api/health, as on the desktop. */
        const val PROBE_TIMEOUT_MS = 3_000
    }
}
