package com.gammapdf.gamma.shell

import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The host contract's transport on the page's WebView, the iPad's Bridge
 * (ipad/GammaIPad/Shell/Navigation.swift) over androidx.webkit
 * (docs/dev/android.md "The bridge"): assets/shell/host.js at each
 * document's start declares `window.gammaNative` with the HostInfo flags,
 * and the message port `gammaAndroidHost` carries both directions
 * (HostWire). Both are given to the server's origin only, and set again
 * for each server before its first load. Messages are taken only from the
 * server's own main frame: nothing else is loaded in the page's WebView,
 * and nothing else should be able to claim the pen.
 *
 * The shell reaches a document through the reply proxy its `{hello}` came
 * with; a send waits for the page's answer and fails when another document
 * has the page, none does, or the WebView is given up. Main-thread confined.
 */
class HostBridge(private val scope: CoroutineScope) {
    /** Answers one message; the result is the reply's value, and a [Refusal] its error. */
    var onMessage: (suspend (WebMessage) -> Map<String, Any?>)? = null

    /**
     * The document the bridge reached is gone: another said hello, a load
     * failed into the WebView's error page, or the bridge was detached.
     * Nothing it began can finish now (the saves it was sending).
     */
    var onDocumentGone: (() -> Unit)? = null

    /** The origin messages are taken from: the server shown. */
    var origin: Origin? = null
        private set

    private var view: WebView? = null
    private var script: ScriptHandler? = null
    private var document: JavaScriptReplyProxy? = null
    private val sends = PendingSends<JavaScriptReplyProxy>()

    private val listener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, replyProxy ->
        received(message, sourceOrigin.toString(), isMainFrame, replyProxy)
    }

    /**
     * Declares the host to [origin]'s documents in [view] from its next load
     * on ([script] is host.js with the flags in), and takes their messages;
     * whatever was set up for another server or WebView goes first.
     */
    fun attach(view: WebView, origin: Origin?, script: String) {
        detach()
        this.view = view
        this.origin = origin
        if (origin == null || !supported) return
        // A host the origin rules refuse (a name no rule can spell) still
        // gets its bridge: the listener checks the origin of each message.
        val rules = try {
            setOf(origin.string).also { WebViewCompat.addWebMessageListener(view, PORT, it, listener) }
        } catch (e: IllegalArgumentException) {
            setOf("*").also { WebViewCompat.addWebMessageListener(view, PORT, it, listener) }
        }
        this.script = WebViewCompat.addDocumentStartJavaScript(view, script, rules)
    }

    /** The WebView is given up (another takes its place, or the activity ends): nothing reaches it any more. */
    fun detach() {
        script?.remove()
        script = null
        view?.let { if (supported) WebViewCompat.removeWebMessageListener(it, PORT) }
        view = null
        origin = null
        pageLeft()
    }

    /**
     * The page shows a document the bridge does not reach (a load failed into
     * the WebView's error page): what was sent waits for no one.
     */
    fun pageLeft() {
        val gone = document != null
        document = null
        sends.keepOnly(null)
        if (gone) onDocumentGone?.invoke()
    }

    /**
     * Delivers [message] and waits for the page's promise: false when it
     * could not be delivered or the page rejected it.
     */
    suspend fun send(message: HostMessage): Boolean = deliver(message) is SendAnswer.Delivered

    /**
     * Delivers [message] and hands back what the page's promise resolved
     * with (`{}` when it resolved with nothing, as a page without a listener
     * for the type does), or null when it could not be delivered or the page
     * rejected it.
     */
    suspend fun ask(message: HostMessage): Any? = (deliver(message) as? SendAnswer.Delivered)?.value

    /** [send] without waiting for the answer. */
    fun post(message: HostMessage) {
        scope.launch { send(message) }
    }

    private suspend fun deliver(message: HostMessage): SendAnswer {
        val to = document ?: return SendAnswer.Failed
        return suspendCancellableCoroutine { waiting ->
            val n = sends.add(to) { answer -> if (waiting.isActive) waiting.resume(answer) }
            try {
                to.postMessage(PortMessage.send(n, message.encode()))
            } catch (e: Exception) {
                sends.fail(n)
            }
        }
    }

    private fun received(message: WebMessageCompat, source: String, mainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        // The wire is strings; reading an ArrayBuffer's data would throw, here on the UI thread.
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val parsed = message.data?.let { PortMessage.parse(it) } ?: return
        val accepted = mainFrame && origin != null && Origin.of(source) == origin
        when (parsed) {
            PortMessage.Hello -> if (accepted && proxy !== document) {
                // A new document has the page: the old one answers nothing more.
                val gone = document != null
                document = proxy
                sends.keepOnly(proxy)
                if (gone) onDocumentGone?.invoke()
            }
            is PortMessage.Result -> if (accepted) sends.answer(parsed.send, parsed.value, parsed.error)
            is PortMessage.Request -> {
                if (!accepted) return reply(proxy, PortMessage.refusal(parsed.id, WebMessage.ONLY_THE_SERVER))
                val decoded = try {
                    WebMessage.decode(parsed.body["msg"])
                } catch (e: IllegalArgumentException) {
                    return reply(proxy, PortMessage.refusal(parsed.id, WebMessage.NOT_UNDERSTOOD))
                }
                scope.launch { reply(proxy, answer(parsed.id, decoded)) }
            }
        }
    }

    private suspend fun answer(id: Any, message: WebMessage): String = try {
        PortMessage.reply(id, onMessage?.invoke(message) ?: emptyMap<String, Any?>())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Refusal) {
        PortMessage.refusal(id, e.message ?: WebMessage.NOT_UNDERSTOOD)
    } catch (e: Exception) {
        Log.w(TAG, "answering ${message.javaClass.simpleName}", e)
        PortMessage.refusal(id, e.message ?: e.javaClass.simpleName)
    }

    private fun reply(proxy: JavaScriptReplyProxy, text: String) {
        try {
            proxy.postMessage(text)
        } catch (e: Exception) {
            // The document that asked is gone.
        }
    }

    companion object {
        /** The port's name, `window.gammaAndroidHost` in the page (host.js). */
        const val PORT = "gammaAndroidHost"
        private const val TAG = "GammaBridge"

        /** Whether this WebView can carry the bridge; one that cannot shows the page as a browser would. */
        val supported: Boolean
            get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }
}
