package com.gammapdf.gamma.shell

import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.gammapdf.gamma.app.Credentials
import com.gammapdf.gamma.app.Prefs
import com.gammapdf.gamma.pen.CssColor

/**
 * The server's page, ported from ipad/GammaIPad/Shell/WebShell.swift: the
 * server's web app in one WebView, the host declared before its bundle runs
 * (HostBridge), the session the bar's switcher reads and the local server's
 * silent sign-in (Session), and the theme the page reports. Under it the
 * page lays out as in a browser on the same screen: one CSS pixel to one dp
 * whatever the system's font size, no zoom, and a document that never
 * scrolls or stretches (the web app scrolls its own panes).
 *
 * A WebView whose renderer the system ended cannot be used again, so it is
 * replaced by a new one ([place] puts it under the pen layer), where the
 * iPad reloads its own. Main-thread confined.
 */
class PageView(
    private val activity: ComponentActivity,
    private val prefs: Prefs,
    private val navigation: Navigation,
    /** assets/shell/host.js with this app's HostInfo in it. */
    private val hostScript: String,
    private val scope: CoroutineScope,
    /** Puts a page WebView where the page is shown (the pen layer), in place of the one before. */
    private val place: (WebView) -> Unit,
) {
    val bridge = HostBridge(scope)

    /** The theme's background, behind the page until it paints and under it. */
    private var backdrop: Int

    var webView: WebView
        private set

    /** The server shown: its address, an origin. */
    var server: String? = null
        private set

    /**
     * The local server's seeded account while the local server is shown
     * (AppModel.login), null for any other server.
     */
    var login: Credentials? = null

    /**
     * A sign-in is due after the next finished load: set by each open, retry
     * and recovery, as the desktop signs in once per open. A person who signs
     * out stays out until the server is opened again.
     */
    private var loginDue = false

    /**
     * What the page's server answered /api/session (its user, default
     * workspace and workspaces: the bar's switcher), null while no one is
     * signed in or it is not known.
     */
    var session: Map<String, Any?>? = null
        private set

    /**
     * The page's theme (`<html data-theme>`), which the chrome pages paint
     * in, kept for the next launch like the background; "" until a page has
     * reported one.
     */
    var themeName: String = prefs.string(THEME_NAME_KEY).orEmpty()
        private set

    /** [session] or [themeName] changed: the shell hands the chrome its state. */
    var onChange: (() -> Unit)? = null

    /** Between a main-frame load's start and its finish: a session read then would ask about the old page. */
    private var loading = false
    private var readingSession = false

    /**
     * The WebView's history still holds the pages of the server shown
     * before: cleared once this server's first page is in it, so Back
     * stays on the server shown (ShellController.back checks that too).
     */
    private var historyBefore = false

    init {
        backdrop = savedBackground(prefs) ?: windowBackground(activity)
        webView = make()
        place(webView)
        navigation.loadInPage = { load(it) }
        navigation.onPageStarted = { view -> if (view === webView) loading = true }
        navigation.onPageFinished = { view ->
            if (view === webView) {
                loading = false
                signInIfDue()
                readSession()
            }
        }
        // The web app writes the workspace (?ws=) with replaceState, and signs in in place.
        navigation.onAddressChanged = { view, address ->
            if (view === webView) {
                // The address committed, where webView.url may already be the next load's.
                if (historyBefore && onServer(address)) {
                    historyBefore = false
                    view.clearHistory()
                }
                readSession()
            }
        }
        navigation.onRendererGone = { view -> if (view === webView) recover() }
    }

    /** The page's address now. */
    val url: String? get() = webView.url

    /** Shows [server]; nothing happens when it is the one shown. */
    fun open(server: String) {
        if (server == this.server) return
        this.server = server
        val origin = Origin.of(server)
        navigation.server = origin
        bridge.attach(webView, origin, hostScript)
        loginDue = true
        session = null
        historyBefore = true
        load(server)
    }

    /** The server's web app on one of its workspaces, as the desktop's switcher opens one (`?ws=`). */
    fun openWorkspace(id: String) {
        val server = server ?: return
        load(server.trimEnd('/') + "/?ws=" + percentEncode(id))
    }

    /** After a failed load: the server's address again. */
    fun retry() {
        val server = server ?: return
        loginDue = true
        load(server)
    }

    /** The page again while it is on the shown server, else the server's address (none has loaded, or a switch to it failed). */
    fun reload() {
        if (onServer(webView.url)) {
            navigation.loading()
            webView.reload()
        } else {
            retry()
        }
    }

    /** [url] in the page's WebView. */
    fun load(url: String) {
        navigation.loading()
        webView.loadUrl(url)
    }

    /**
     * The theme's background behind and under the page, kept for the next
     * launch so the app opens in the colour it closed in, and its name for
     * the chrome. Null when [hex] is no colour.
     */
    fun paint(hex: String, name: String): Int? {
        if (name != themeName) {
            themeName = name
            prefs.putString(THEME_NAME_KEY, name)
            onChange?.invoke()
        }
        val color = CssColor.parse(hex) ?: return null
        backdrop = color
        webView.setBackgroundColor(color)
        prefs.putString(BACKGROUND_KEY, hex)
        return color
    }

    /**
     * Reads the session while the page is on the shown server and not
     * loading, one read at a time; the change is announced whatever it is.
     */
    fun readSession() {
        val server = server
        if (readingSession || loading || server == null || !onServer(webView.url)) return
        readingSession = true
        scope.launch {
            val answer = try {
                Session.read(server)
            } finally {
                readingSession = false
            }
            // An answer for a server no longer shown is no one's.
            if (server == this@PageView.server) session = answer
            onChange?.invoke()
        }
    }

    /** Only on the local server's own page: nothing is signed in on a remote one. */
    private fun signInIfDue() {
        val login = login
        val server = server
        if (!loginDue || login == null || server == null || !onServer(webView.url)) return
        loginDue = false
        val view = webView
        scope.launch {
            if (Session.signIn(server, login) && view === webView && server == this@PageView.server) {
                navigation.loading()
                view.reload()
            }
        }
    }

    /**
     * The renderer was ended by the system (memory, mostly): a new WebView
     * takes the old one's place and loads where it was, and the web app
     * restores itself as after any reload.
     */
    private fun recover() {
        val dead = webView
        val address = dead.url
        webView = make()
        bridge.attach(webView, Origin.of(server), hostScript)
        place(webView)
        (dead.parent as? ViewGroup)?.removeView(dead)
        dead.destroy()
        loginDue = true
        loading = false
        readingSession = false
        if (address != null && onServer(address)) load(address) else retry()
    }

    fun pause() = webView.onPause()

    fun resume() = webView.onResume()

    /** The activity is going: the WebView with it. */
    fun destroy() {
        bridge.detach()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun onServer(page: String?): Boolean {
        val origin = Origin.of(server) ?: return false
        return Origin.of(page) == origin
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun make(): WebView = WebView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The app has its own scale (Settings › Appearance); the system's font size would double it.
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // The recordings' player may start after an await.
            mediaPlaybackRequiresUserGesture = false
            // target=_blank and window.open reach onCreateWindow, which sends them out of the app.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
        }
        // The app has dark themes of its own: the page is never darkened for it.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(backdrop)
        // The shell keeps the page clear of the system bars itself, so the
        // page's safe areas are none (app.css pads by them).
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ -> WindowInsetsCompat.CONSUMED }
        webViewClient = navigation
        webChromeClient = navigation.chromeClient
        setDownloadListener(navigation.downloads)
    }

    companion object {
        const val BACKGROUND_KEY = "gamma.theme.background"
        const val THEME_NAME_KEY = "gamma.theme.name"

        /** The page's background as it was last painted, for the window before the page paints. */
        fun savedBackground(prefs: Prefs): Int? = CssColor.parse(prefs.string(BACKGROUND_KEY))

        /** The window's own background in the system's light or dark. */
        fun windowBackground(activity: ComponentActivity): Int {
            val value = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
            return value.data
        }
    }
}
