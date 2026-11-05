package com.gammapdf.gamma.shell

import android.content.ActivityNotFoundException
import android.net.Uri
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.IOException
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.gammapdf.gamma.R

/**
 * The Gamma Cloud sign-in, run: the flow CloudWire describes, as the iPad
 * runs it (ipad/GammaIPad/Shell/CloudSignIn.swift) with Android's parts
 * (docs/dev/android.md "Gamma Cloud sign-in"). The loopback is a socket
 * on 127.0.0.1 (LoopbackReturn); app-start and app-claim are sent from here
 * with the page's cookies (CloudFlow); the browser is an Auth Tab, or a
 * Custom Tab where the browser has none, whose return comes through
 * CloudReturnActivity. The claim goes as soon as the loopback has the
 * result, and the WebView goes on with the session cookie the claim set,
 * whether or not the browser's tab has closed by then.
 *
 * One sign-in at a time. A start while one asks the server or claims is a
 * second tap, and does nothing; a start while the browser is open replaces
 * that sign-in, since a Custom Tab says nothing when the person closes it.
 * The listener stops when its sign-in ends, when another replaces it, when
 * the shell shows another server, and after ten minutes.
 *
 * Main-thread confined. Made in the activity's onCreate: it registers the
 * Auth Tab's result there.
 */
class CloudSignIn(private val activity: ComponentActivity, private val scope: CoroutineScope) {
    private enum class Phase { ASKING, BROWSER, CLAIMING }

    private class Attempt(val server: Origin, val load: (String) -> Unit, val alert: (String) -> Unit) {
        val pkce = Pkce()

        /** The browser's answer, kept by the loopback's thread before it sends the browser back. */
        @Volatile var answer: CloudAnswer? = null

        /** The answer, or null when the browser closed without one. */
        val done = CompletableDeferred<CloudAnswer?>()
        var phase = Phase.ASKING
        var loopback: LoopbackReturn? = null
        var job: Job? = null
    }

    private var attempt: Attempt? = null

    private val authTab = AuthTabIntent.registerActivityResultLauncher(activity) { tabClosed() }

    init {
        current = WeakReference(this)
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                attempt?.let(::end)
                if (current?.get() === this) current = null
            }
        })
    }

    /**
     * The page went to [startUrl] (`<server>/api/auth/cloud/start?…`) on
     * [server]. When the sign-in is done, [load] takes the WebView on (the
     * claim has set the session cookie by then); [alert] tells the person
     * why it could not be.
     */
    fun start(startUrl: String, server: Origin, load: (String) -> Unit, alert: (String) -> Unit) {
        attempt?.let {
            if (it.phase != Phase.BROWSER) return
            end(it)
        }
        val next = Attempt(server, load, alert)
        attempt = next
        next.job = scope.launch(Dispatchers.Main.immediate) { run(next, startUrl) }
    }

    /**
     * The shell shows [server] now (Navigation.server): a sign-in begun on
     * another ends, whatever its phase. A browser outside the app's task (a
     * Custom Tab moved to the browser, or no Custom Tabs at all) leaves the
     * app usable meanwhile, and its landing would load the old server's page
     * into a shell set up for the new one.
     */
    fun serverChanged(server: Origin?) {
        attempt?.takeIf { it.server != server }?.let(::end)
    }

    /**
     * The browser came back through gamma-android://cloud-signin
     * (CloudReturnActivity, after a Custom Tab). The loopback sends it there
     * only once it has the answer, so a return without one is not this
     * sign-in's and changes nothing.
     */
    fun onReturn(uri: Uri) {
        if (uri.scheme != CloudWire.CALLBACK_SCHEME || uri.host != RETURN_HOST) return
        val waiting = attempt?.takeIf { it.phase == Phase.BROWSER } ?: return
        waiting.answer?.let { waiting.done.complete(it) }
    }

    /** The Auth Tab closed: by the redirect to the app's scheme after the answer, or by the person. */
    private fun tabClosed() {
        val waiting = attempt?.takeIf { it.phase == Phase.BROWSER } ?: return
        waiting.done.complete(waiting.answer)
    }

    private suspend fun run(a: Attempt, startUrl: String) {
        try {
            when (val answer = answer(a, startUrl)) {
                is CloudAnswer.Result -> claim(a, answer.value)
                is CloudAnswer.Error -> a.alert(activity.getString(R.string.cloud_sign_in_did_not_finish, answer.message))
                null -> {} // the person closed the browser, or it took too long
            }
        } catch (e: Refusal) {
            a.alert(activity.getString(R.string.cloud_sign_in_failed, e.message))
        } catch (e: IOException) {
            a.alert(activity.getString(R.string.cloud_sign_in_failed, describe(e)))
        } catch (e: ActivityNotFoundException) {
            a.alert(activity.getString(R.string.cloud_sign_in_no_browser))
        } finally {
            if (attempt === a) attempt = null
        }
    }

    /**
     * What the browser brought back: app-start for the loopback's address,
     * then the browser until the answer comes; null when the server is too
     * old (the person is told), the browser closed first, or the wait ran out.
     */
    private suspend fun answer(a: Attempt, startUrl: String): CloudAnswer? {
        val loopback = LoopbackReturn { answer ->
            a.answer = answer
            a.done.complete(answer)
        }
        a.loopback = loopback
        return coroutineScope {
            launch { loopback.serve() }
            try {
                val body = CloudWire.AppStart.from(startUrl, loopback.returnAddress, a.pkce.challenge)
                val cookie = CookieManager.getInstance().getCookie(a.server.string)
                val started = withContext(Dispatchers.IO) { CloudFlow.appStart(a.server, body, cookie) }
                val authorize = CloudWire.authorizeUrl(started.status, started.text)
                if (authorize == null) {
                    a.alert(activity.getString(R.string.cloud_sign_in_older_server))
                    null
                } else {
                    browse(authorize)
                    a.phase = Phase.BROWSER
                    withTimeoutOrNull(CloudFlow.WAIT_MILLIS) { a.done.await() }
                }
            } finally {
                a.phase = Phase.CLAIMING
                loopback.close()
            }
        }
    }

    /**
     * The account server's sign-in in the system browser, where Google's
     * works: an Auth Tab, which hands back the redirect to the app's scheme
     * itself, when the browser has one; else a Custom Tab. Not ephemeral,
     * so the browser's own Google or GitHub session can be used.
     */
    private fun browse(url: String) {
        val uri = url.toUri()
        val browser = CustomTabsClient.getPackageName(activity, null)
        if (browser != null && CustomTabsClient.isAuthTabSupported(activity, browser)) {
            val tab = AuthTabIntent.Builder().build()
            tab.intent.setPackage(browser)
            tab.launch(authTab, uri, CloudWire.CALLBACK_SCHEME)
        } else {
            val tab = CustomTabsIntent.Builder().build()
            if (browser != null) tab.intent.setPackage(browser)
            tab.launchUrl(activity, uri)
        }
    }

    /** app-claim, its session cookie put where the WebView reads cookies, and the WebView on to where it leads. */
    private suspend fun claim(a: Attempt, result: String) {
        val cookies = CookieManager.getInstance()
        val request = CloudWire.claimRequest(a.server, result, a.pkce.verifier)
        val cookie = cookies.getCookie(a.server.string)
        val claimed = withContext(Dispatchers.IO) {
            CloudFlow.claim(a.server, request, cookie).also { claimed ->
                for (header in claimed.cookies) cookies.setCookie(a.server.string, header)
                cookies.flush()
            }
        }
        a.load(claimed.landing)
    }

    private fun end(a: Attempt) {
        a.loopback?.close()
        a.job?.cancel()
        if (attempt === a) attempt = null
    }

    private fun describe(e: IOException): String = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    companion object {
        /** The host of the app's return address, gamma-android://cloud-signin (CloudWire.loopbackResponse). */
        private const val RETURN_HOST = "cloud-signin"

        /** The sign-in of the activity alive now, for CloudReturnActivity. Main thread. */
        private var current: WeakReference<CloudSignIn>? = null

        /** CloudReturnActivity's hand-over of the browser's return. */
        fun returned(uri: Uri) {
            current?.get()?.onReturn(uri)
        }
    }
}
