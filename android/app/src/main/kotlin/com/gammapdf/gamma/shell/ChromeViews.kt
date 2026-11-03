package com.gammapdf.gamma.shell

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The desktop app's own chrome on Android, ported from ipad/GammaIPad/Shell/
 * Chrome.swift: its shell bar and its launcher (desktop/ui/bar.html and
 * launcher.html, bundled under assets/chrome/ by build.gradle.kts), each in a
 * WebView of its own over the page, served by WebViewAssetLoader from
 * https://appassets.androidplatform.net/assets/chrome/ (ChromePages), and
 * the `gammaShell` they call: assets/shell/chrome.js at each document's
 * start, over the message port `gammaShellHost`, both for that origin only
 * (docs/dev/android.md "The chrome"). Calls are answered by [call] as
 * Electron's preload answers them, and the shell's state is pushed to every
 * page that has said hello. The page's WebView never sees any of it.
 * Main-thread confined.
 */
class ChromeViews(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    /** Answers one call by its method's name: the reply's value (JSON values only), or a throw the page shows. */
    private val call: suspend (method: String, args: List<Any?>) -> Any?,
) {
    /** A page's renderer ended, and its WebView with it: the shell makes another in its place. */
    var onGone: ((WebView) -> Unit)? = null

    /** What each live view shows, and how its document is reached once it said hello. */
    private class Page(var url: String? = null, var document: JavaScriptReplyProxy? = null)

    private val pages = LinkedHashMap<WebView, Page>()
    private val script = activity.assets.open("shell/chrome.js").bufferedReader().use { it.readText() }
    private val assets = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
        .build()

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assets.shouldInterceptRequest(request.url)

        // Only the bundled pages load here; a link out of them leaves the app's window.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith(ChromePages.ROOT)) return false
            when (UrlParts.schemeOf(url)) {
                "http", "https" -> openOutside(activity, url)
                "about", "blob", "data", "javascript", null -> {}
                else -> openSystem(activity, url)
            }
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onGone?.invoke(view)
            return true
        }
    }

    private val listener = WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, replyProxy ->
        received(view, message, sourceOrigin.toString(), isMainFrame, replyProxy)
    }

    /**
     * A WebView for one of the pages: the bar see-through around its strip
     * and open menu, never scrolling; the launcher in [background], the
     * theme's colour, until it paints.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun make(bar: Boolean, background: Int): WebView {
        val view = WebView(activity)
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        view.setBackgroundColor(if (bar) Color.TRANSPARENT else background)
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled = !bar
        view.isHorizontalScrollBarEnabled = false
        // Placed below the status bar by the shell: the pages pad for no safe area.
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, _ -> WindowInsetsCompat.CONSUMED }
        view.webViewClient = client
        if (HostBridge.supported) {
            val origin = setOf(ChromePages.ORIGIN)
            WebViewCompat.addWebMessageListener(view, PORT, origin, listener)
            WebViewCompat.addDocumentStartJavaScript(view, script, origin)
        }
        pages[view] = Page()
        return view
    }

    /** Loads [url], a chrome page, in [view]; its new document says hello before it hears the state. */
    fun load(view: WebView, url: String) {
        val page = pages[view] ?: return
        page.url = url
        page.document = null
        view.loadUrl(url)
    }

    /** What [view] was last asked to show. */
    fun urlOf(view: WebView): String? = pages[view]?.url

    /**
     * Hands the state to every page's `onState` listeners. A page not loaded
     * yet has none, and asks for the state itself once it runs.
     */
    fun push(state: Map<String, Any?>) {
        if (pages.values.none { it.document != null }) return
        val text = PortMessage.state(state)
        for (page in pages.values) page.document?.let { post(it, text) }
    }

    /** An open dropdown in [view]'s page closes (bar.html closes it on Escape). */
    fun escape(view: WebView) {
        view.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'}))", null)
    }

    /** [view] is no longer shown: it goes, with its page. */
    fun destroy(view: WebView) {
        pages.remove(view) ?: return
        if (HostBridge.supported) WebViewCompat.removeWebMessageListener(view, PORT)
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    /** Every view goes (the activity does). */
    fun destroyAll() {
        for (view in pages.keys.toList()) destroy(view)
    }

    fun pause() = pages.keys.forEach { it.onPause() }

    fun resume() = pages.keys.forEach { it.onResume() }

    private fun received(view: WebView, message: WebMessageCompat, source: String, mainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        val page = pages[view] ?: return
        // Strings only, as on the page's port (HostBridge): an ArrayBuffer's data would throw.
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val parsed = message.data?.let { PortMessage.parse(it) } ?: return
        // Only the bundled pages call: a frame inside one is not the page.
        val ours = ChromePages.accepts(view.url, mainFrame) && Origin.of(source) == Origin.of(ChromePages.ORIGIN)
        when (parsed) {
            PortMessage.Hello -> if (ours) page.document = proxy
            is PortMessage.Request -> {
                val method = parsed.body["method"] as? String
                if (!ours || method == null) return post(proxy, PortMessage.refusal(parsed.id, ChromePages.ONLY_OUR_PAGES))
                val args = (parsed.body["args"] as? List<*>)?.toList() ?: emptyList()
                scope.launch {
                    val reply = try {
                        PortMessage.reply(parsed.id, call(method, args))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        PortMessage.refusal(parsed.id, e.message ?: e.javaClass.simpleName)
                    }
                    post(proxy, reply)
                }
            }
            is PortMessage.Result -> {}
        }
    }

    private fun post(document: JavaScriptReplyProxy, text: String) {
        try {
            document.postMessage(text)
        } catch (e: Exception) {
            // The page that asked is gone.
        }
    }

    companion object {
        /** The port's name, `window.gammaShellHost` in the chrome pages (chrome.js). */
        const val PORT = "gammaShellHost"
    }
}
