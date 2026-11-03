package com.gammapdf.gamma.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException
import com.gammapdf.gamma.json.Json

/**
 * The shell's own HTTP requests, made as the page's would be: Gamma Cloud's
 * app-start and app-claim (shell/CloudSignIn.kt) and the files it saves from
 * a URL (shell/SavePresenter.kt). The WebView's cookies come in as
 * [cookies], CookieManager's getCookie on a device, so this stays testable.
 * Redirects are followed here, one hop at a time, each checked and given
 * cookies or not by its own address ([SaveHops]): HttpURLConnection would
 * carry a Cookie header set on the first request to every host it is sent
 * on to.
 */
internal object Fetch {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 60_000
    const val MAX_REDIRECTS = 5

    /**
     * A GET of the http(s) [url] that answered 2xx, its body still to read
     * (the caller disconnects it), after at most [MAX_REDIRECTS] redirects
     * to http(s). Each hop is held to [hops]: one it refuses is not asked
     * at all, and only one it sends cookies to gets [cookies] for its
     * address. Anything else throws an IOException with the server's
     * `detail`, or the status. Nothing is retried: some downloads count (a
     * job's artifact is marked downloaded when it is fetched). Blocking, a
     * name lookup included: off the main thread.
     */
    fun get(url: String, userAgent: String?, hops: SaveHops, cookies: (String) -> String?): HttpURLConnection {
        var address = url
        repeat(MAX_REDIRECTS + 1) {
            hops.refusal(address)?.let { throw IOException(it) }
            val connection = open(address)
            connection.instanceFollowRedirects = false
            if (!userAgent.isNullOrBlank()) connection.setRequestProperty("User-Agent", userAgent)
            if (hops.sendsCookies(address)) {
                cookies(address)?.takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("Cookie", it) }
            }
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
            when (status) {
                in 200..299 -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    address = redirect(address, location) ?: throw IOException("HTTP $status")
                }
                else -> {
                    val text = text(connection)
                    connection.disconnect()
                    throw IOException(problem(status, text))
                }
            }
        }
        throw IOException("Too many redirects")
    }

    /**
     * A POST of [body] to the http(s) [url] with [headers] and the [cookie]
     * header when there is one, redirects not followed: the connection with
     * its status read, to read its headers and body from.
     */
    fun post(url: String, headers: Map<String, String>, body: ByteArray, cookie: String?): HttpURLConnection {
        val connection = open(url)
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (!cookie.isNullOrEmpty()) connection.setRequestProperty("Cookie", cookie)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
        return connection
    }

    /** A response's body as text (the error body for a status of 400 or more), at most [limit] bytes; "" when it has none. */
    fun text(connection: HttpURLConnection, limit: Int = 64 * 1024): String {
        val stream = try {
            if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        } catch (e: IOException) {
            null
        } ?: return ""
        return try {
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() < limit) {
                    val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: IOException) {
            ""
        }
    }

    /** What a refusal says: the `detail` of the server's error body, else the status. */
    fun problem(status: Int, text: String): String =
        (Json.objectOrNull(text)?.get("detail") as? String)?.takeIf { it.isNotBlank() } ?: "HTTP $status"

    /** Where a redirect from [from] to [location] goes, when that is http(s). */
    fun redirect(from: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val target = try {
            URL(URL(from), location.trim()).toString()
        } catch (e: MalformedURLException) {
            return null
        }
        return target.takeIf { UrlParts.schemeOf(it).let { scheme -> scheme == "http" || scheme == "https" } }
    }

    private fun open(url: String): HttpURLConnection {
        val scheme = UrlParts.schemeOf(url)
        if (scheme != "http" && scheme != "https") throw IOException("Not a web address: $url")
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: MalformedURLException) {
            throw IOException("Not a web address: $url", e)
        }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        return connection
    }
}

/**
 * What a save's requests may reach, hop by hop (Fetch.get), for a page of
 * [server]'s (docs/dev/android.md "Saving files"). That page may be any
 * server the person added, and a request the shell makes is held to none
 * of the browser's rules (CORS, SameSite), so the shell holds it to these:
 * the WebView's cookies go to the server shown alone ([sendsCookies]), and
 * an address on this device that is not the server shown (the local
 * server, on its loopback port) is not asked at all ([refusal]). A
 * redirect is a hop like any other, so it gets around neither.
 */
internal class SaveHops(
    private val server: Origin?,
    /** Whether [host] is this device: a loopback or unspecified address, or a name for one. May look the name up. */
    private val onDevice: (String) -> Boolean = ::isOnDevice,
) {
    /** Why [url] is not fetched, or null when it is. */
    fun refusal(url: String): String? {
        // The cookie store and the connection parse an address too; only one
        // all of them read alike is asked (no user name, escape, backslash or
        // non-ASCII letter in its host).
        if (!PLAIN.containsMatchIn(url)) return NOT_PLAIN
        val origin = Origin.of(url) ?: return NOT_PLAIN
        return if (origin != server && onDevice(origin.host)) ON_DEVICE else null
    }

    /** Whether the WebView's cookies for [url] go with it: only on the server shown. */
    fun sendsCookies(url: String): Boolean = server != null && Origin.of(url) == server

    companion object {
        const val NOT_PLAIN = "Gamma for Android does not fetch an address written this way."
        const val ON_DEVICE = "Gamma for Android fetches nothing on this device but the server shown."

        private val PLAIN = Regex("^https?://([A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.]+])(:[0-9]{1,5})?([/?#]|$)", RegexOption.IGNORE_CASE)

        /**
         * The lookup the connection itself makes (InetAddress, which reads
         * 127.1 and 0x7f.1 as Android's resolver does); a name that does
         * not resolve is no address here, and its connection fails anyway.
         */
        fun isOnDevice(host: String): Boolean = try {
            InetAddress.getAllByName(host).any { it.isLoopbackAddress || it.isAnyLocalAddress }
        } catch (e: UnknownHostException) {
            false
        }
    }
}
