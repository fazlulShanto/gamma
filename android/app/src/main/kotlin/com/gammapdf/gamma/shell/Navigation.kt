package com.gammapdf.gamma.shell

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.DownloadListener
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.gammapdf.gamma.R

/**
 * The page WebView's clients, ported from ipad/GammaIPad/Shell/
 * Navigation.swift: the server's pages stay and everything else leaves the
 * app's window (Route), other sites in a Custom Tab, `target=_blank` the same
 * way, other apps' links to the system, the Gamma Cloud start to CloudSignIn,
 * downloads to SavePresenter, the file chooser and the microphone to their
 * services, and the page's own dialogs. A main-frame failure goes to the
 * launcher; a renderer the system ended is replaced (PageView). Main-thread
 * confined, as WebView calls it.
 */
class Navigation(
    private val activity: ComponentActivity,
    private val cloudSignIn: CloudSignIn,
    private val fileChooser: FileChooser,
    private val microphone: Microphone,
    private val saver: SavePresenter,
) : WebViewClient() {
    /**
     * The server shown, whose pages stay in the WebView. A Gamma Cloud
     * sign-in begun on another ends when it changes (CloudSignIn.serverChanged).
     */
    var server: Origin? = null
        set(value) {
            if (value != field) cloudSignIn.serverChanged(value)
            field = value
        }

    /** Loads a URL in the page's WebView, whichever it is now (after a claim, the cloud sign-in's landing). */
    var loadInPage: ((String) -> Unit)? = null

    /** A main-frame load failed (the launcher's sentence), or a page arrived (null). */
    var onLoad: ((String?) -> Unit)? = null

    /**
     * A main-frame load began; it finished; the page's history took an
     * address, committed (a new page's, or one replaceState wrote).
     */
    var onPageStarted: ((WebView) -> Unit)? = null
    var onPageFinished: ((WebView) -> Unit)? = null
    var onAddressChanged: ((WebView, String?) -> Unit)? = null

    /** The WebView's renderer is gone: the WebView cannot be used again. */
    var onRendererGone: ((WebView) -> Unit)? = null

    /**
     * The address whose load just failed, reported once: the WebView's
     * error page commits under it and is no page of the server's. Cleared by
     * each load the shell starts (`loading`).
     */
    private var failed: String? = null

    private val main = Handler(Looper.getMainLooper())

    /** The shell loads anew: a failure of it is news, and the page that arrives is one. */
    fun loading() {
        failed = null
    }

    // --- navigations -----------------------------------------------------------------------

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (Route.of(url, server, request.isForMainFrame)) {
            Route.LOAD -> false
            Route.CLOUD_SIGN_IN -> {
                // The landing is loaded only while the server it signed in to is the one shown.
                server?.let { origin ->
                    cloudSignIn.start(url, origin, { landing -> if (server == origin) loadInPage?.invoke(landing) }, ::alert)
                }
                true
            }
            Route.BROWSER -> {
                openOutside(activity, url)
                true
            }
            Route.SYSTEM -> {
                openSystem(activity, url)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        onPageStarted?.invoke(view)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPageFinished?.invoke(view)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        onAddressChanged?.invoke(view, url)
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        if (url != null && url == failed) return
        failed = null
        onLoad?.invoke(null)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val url = request.url.toString()
        if (url == failed) return
        val message = PageLoad.failure(error.errorCode, error.description?.toString()) ?: return
        failed = url
        onLoad?.invoke(message)
    }

    /**
     * A certificate the WebView does not trust is refused, as everywhere; the
     * load of the server's own page that it stops is a failure the launcher
     * shows (the page's address is the one loading).
     */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        val url = error.url ?: return
        if (url != view.url || Origin.of(url) != server || url == failed) return
        failed = url
        onLoad?.invoke(PageLoad.INSECURE)
    }

    // An http error page is still a page, as on the iPad: onReceivedHttpError is not a failure.

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone?.invoke(view)
        return true
    }

    // --- the page's windows, dialogs, files, microphone and downloads ------------------------

    /** The WebView's chrome client: new windows, dialogs, the file chooser and the microphone. */
    val chromeClient: WebChromeClient = object : WebChromeClient() {
        /**
         * `target=_blank` and window.open: the address is learned in a
         * WebView of its own that never shows, then routed as the iPad
         * routes a new window (never in the app: same-origin links leave too).
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val catcher = WebView(activity)
            var done = false
            // Never attached, so its own post() would never run.
            val release = Runnable {
                if (!done) {
                    done = true
                    catcher.destroy()
                }
            }
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(window: WebView, request: WebResourceRequest): Boolean {
                    if (!done) openWindow(request.url.toString())
                    main.post(release)
                    return true
                }
            }
            // A window that never navigates (window.open() then written into) is let go.
            main.postDelayed(release, WINDOW_WAIT_MS)
            transport.webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (activity.isFinishing) return false
            AlertDialog.Builder(activity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (activity.isFinishing) return false
            AlertDialog.Builder(activity)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = fileChooser.show(filePathCallback, fileChooserParams)

        override fun onPermissionRequest(request: PermissionRequest) {
            microphone.request(request, server)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            microphone.cancel(request)
        }
    }

    /**
     * What the page downloads by itself rather than through `save` (an
     * attachment it navigated to, an old page's <a download>), with the
     * shown server's cookies on its hops alone (SavePresenter.download).
     */
    val downloads = DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        saver.download(url, userAgent, contentDisposition, mimeType, server)
    }

    private fun openWindow(url: String) {
        when (UrlParts.schemeOf(url)) {
            "http", "https" -> openOutside(activity, url)
            "about", "blob", "data", "javascript", null -> {} // nothing a browser outside can open
            else -> openSystem(activity, url)
        }
    }

    /** A message of the shell's own (the cloud sign-in's), titled with the app's name. */
    fun alert(message: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.app_name)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private companion object {
        const val WINDOW_WAIT_MS = 10_000L
    }
}

/** [url] in a Custom Tab over the app (the iPad's Safari view), else in whatever opens it. */
internal fun openOutside(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    } catch (e: ActivityNotFoundException) {
        openSystem(context, url)
    }
}

/** A link for another app (mailto:, tel:, an app's own scheme), handed to the system. */
internal fun openSystem(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
    }
}
