package com.gammapdf.gamma.shell

import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.gammapdf.gamma.app.Credentials

/**
 * The page's session, read and made by the shell itself with the WebView's
 * own cookie (CookieManager): the bar's switcher reads /api/session, and the
 * local server's page is signed in with its seeded account, as the iPad's
 * WebShell does in a content world of its own (ipad/GammaIPad/Shell/
 * WebShell.swift sessionScript and autoLoginScript; docs/dev/android.md "The
 * shell"). A WebView has no such world, so this runs natively and the page
 * never sees the password. The requests run on Dispatchers.IO; CookieManager
 * is used on the main thread, where its callbacks come.
 */
object Session {
    private const val TIMEOUT_MS = 10_000
    private const val BODY_LIMIT = 4 shl 20

    /** The session of [server]'s page (SessionWire.session), or null when no one is signed in or it could not be read. */
    suspend fun read(server: String): Map<String, Any?>? {
        val cookie = CookieManager.getInstance().getCookie(server)
        return withContext(Dispatchers.IO) {
            val (status, body) = request(server + SessionWire.SESSION_PATH, cookie) ?: return@withContext null
            SessionWire.session(status, body)
        }
    }

    /**
     * Signs [server]'s page in with [login] unless someone is signed in
     * already, the cookie landing in the WebView's store: true when the page
     * should load again to show it.
     */
    suspend fun signIn(server: String, login: Credentials): Boolean {
        val manager = CookieManager.getInstance()
        val cookie = manager.getCookie(server)
        val cookies = withContext(Dispatchers.IO) {
            val (status, body) = request(server + SessionWire.SESSION_PATH, cookie) ?: return@withContext null
            if (SessionWire.session(status, body) != null) return@withContext null
            login(server + SessionWire.LOGIN_PATH, cookie, SessionWire.loginBody(login))
        } ?: return false
        for (header in cookies) set(manager, server, header)
        withContext(Dispatchers.IO) { manager.flush() }
        return true
    }

    private suspend fun set(manager: CookieManager, url: String, header: String): Boolean =
        suspendCancellableCoroutine { done -> manager.setCookie(url, header) { done.resume(it == true) } }

    /** GET [url] with [cookie]: its status and body, or null when it could not be asked. Blocking. */
    private fun request(url: String, cookie: String?): Pair<Int, String>? {
        val connection = open(url, cookie) ?: return null
        return try {
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            status to read(if (status >= 400) connection.errorStream else connection.inputStream)
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** POST [body] to /api/login: the Set-Cookie headers of a sign-in that worked, else null. Blocking. */
    private fun login(url: String, cookie: String?, body: String): List<String>? {
        val connection = open(url, cookie) ?: return null
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status in 200..299) SessionWire.setCookies(connection.headerFields) else null
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, cookie: String?): HttpURLConnection? = try {
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
        }
    } catch (e: Exception) {
        null
    }

    private fun read(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < BODY_LIMIT) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }
}
