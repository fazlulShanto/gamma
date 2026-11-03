package com.gammapdf.gamma.shell

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The steps of the Gamma Cloud sign-in (shell/CloudSignIn.kt) that need no
 * device: the two requests the shell sends itself, and reading what the
 * browser and the server answer. CloudWire holds the contract; the iPad
 * sends app-start from inside the page and app-claim from its web view
 * (ipad/GammaIPad/Shell/CloudSignIn.swift), which Android cannot: it has
 * no script world the page cannot reach, and a WebView's own POST cannot
 * carry app-claim's header (docs/dev/android.md "Gamma Cloud sign-in").
 */
object CloudFlow {
    /** The most of a request the loopback reads for its first line, as the iPad's listener. */
    const val HEAD_LIMIT = 16 * 1024

    /**
     * How long a sign-in waits for the browser before its listener stops:
     * a Custom Tab says nothing when the person closes it, and making an
     * account on the way takes a while.
     */
    const val WAIT_MILLIS = 10 * 60 * 1000L

    /**
     * A request's first line with its CRLF, which may come in pieces: read
     * from [input] up to the first CRLF, [limit] bytes or the end of the
     * stream, whichever comes first, and nothing past it. It is what
     * CloudWire.loopbackAnswer reads. Give it a buffered stream.
     */
    fun requestHead(input: InputStream, limit: Int = HEAD_LIMIT): String {
        val out = ByteArrayOutputStream()
        var previous = -1
        while (out.size() < limit) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            if (previous == '\r'.code && b == '\n'.code) break
            previous = b
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Reads the rest of a request's head, after the first line
     * [requestHead] read, up to the empty line that ends it, [limit] bytes
     * or the end of the stream. A socket closed with bytes unread sends a
     * reset in place of a close, and the browser may then drop the reply.
     */
    fun skipHead(input: InputStream, limit: Int) {
        var last = 0x0d0a // the first line's CRLF; the head ends at CRLF CRLF
        var read = 0
        while (read < limit) {
            val b = input.read()
            if (b < 0) return
            read += 1
            last = (last shl 8) or b
            if (last == 0x0d0a0d0a) return
        }
    }

    /** What app-start answered: its status and body, for CloudWire.authorizeUrl. */
    data class Answer(val status: Int, val text: String)

    /**
     * app-start, sent with the page's [cookie], so a Link carries the
     * session it links to, exactly as the page's own fetch would.
     */
    fun appStart(origin: Origin, body: CloudWire.AppStart, cookie: String?): Answer {
        val headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json", "Cache-Control" to "no-store")
        val connection = Fetch.post(CloudWire.appStartUrl(origin), headers, body.json.toByteArray(Charsets.UTF_8), cookie)
        try {
            return Answer(connection.responseCode, Fetch.text(connection))
        } finally {
            connection.disconnect()
        }
    }

    /** What app-claim answered: where the WebView goes next, and the session cookie it set for that. */
    data class Claimed(val landing: String, val cookies: List<String>)

    /**
     * app-claim: the [request] CloudWire built, sent with the page's
     * [cookie] and its redirect not followed. The server's Set-Cookie
     * headers go into the WebView's cookies; its redirect is where the
     * WebView goes ([landing]). Throws a [Refusal] when the server did not
     * redirect, an IOException when it could not be asked.
     */
    fun claim(origin: Origin, request: CloudWire.ClaimRequest, cookie: String?): Claimed {
        val connection = Fetch.post(request.url, request.headers + ("Cache-Control" to "no-store"),
                                    request.body.toByteArray(Charsets.UTF_8), cookie)
        try {
            val status = connection.responseCode
            val text = if (status in 300..399) "" else Fetch.text(connection)
            return Claimed(landing(origin, status, connection.getHeaderField("Location"), text), setCookies(connection.headerFields))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Where the WebView goes once app-claim answered [status] with
     * [location]: the server's redirect, on to `next` signed in or to the
     * login page with `?cloud_error=`, on the server at [origin]. A
     * redirect off the server lands on its home instead. Anything but a
     * redirect throws a [Refusal] with the server's `detail` (from [text],
     * the body), or the status.
     */
    fun landing(origin: Origin, status: Int, location: String?, text: String): String {
        val target = location?.trim().orEmpty()
        if (status !in 300..399 || target.isEmpty()) throw Refusal(Fetch.problem(status, text))
        return when {
            target.startsWith("/") && !target.startsWith("//") -> origin.string + target
            Origin.of(target) == origin -> target
            else -> origin.string + "/"
        }
    }

    /**
     * The Set-Cookie headers among a response's [fields]
     * (HttpURLConnection.headerFields: names in any case, the status line
     * under null).
     */
    fun setCookies(fields: Map<String?, List<String>>): List<String> =
        fields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
}
