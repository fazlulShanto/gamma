package com.gammapdf.gamma.shell

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import com.gammapdf.gamma.json.Json

// Gamma Cloud sign-in from the Android app: the desktop app's flow
// (desktop/lib/cloudSignIn.js) as the iPad runs it (ipad/GammaIPad/Shell/
// CloudSignIn.swift), so the server needs nothing new. Google refuses to sign
// in inside an embedded WebView, so the page's own start (the login page's
// button, Settings' Link) is taken over: the shell listens on a free loopback
// port, asks the server for a sign-in that reports there (app-start, with the
// S256 challenge of a verifier only the shell keeps), and opens the account
// server in the system browser (an Auth Tab or a Custom Tab), where Google's
// sign-in works. The server sends that browser to
// http://127.0.0.1:<port>/cloud-signin with a one-time result; the listener
// keeps it and redirects the browser to gamma-android://cloud-signin, which
// brings the app back. The app then redeems the result with the verifier
// (app-claim), and the session cookie lands where the web app runs. Only
// this device reaches that loopback address (RFC 8252 §7.3), so a sign-in
// someone else started never hands its result to them. Here is the contract
// with the server, as pure functions; shell/CloudSignIn.kt runs the flow
// (docs/dev/android.md "Gamma Cloud sign-in").

/** What the browser brought back to the loopback address. */
sealed interface CloudAnswer {
    data class Result(val value: String) : CloudAnswer
    data class Error(val message: String) : CloudAnswer
}

object CloudWire {
    const val START_PATH = "/api/auth/cloud/start"
    const val APP_START_PATH = "/api/auth/cloud/app-start"
    const val CLAIM_PATH = "/api/auth/cloud/app-claim"
    const val RETURN_PATH = "/cloud-signin"
    const val CALLBACK_SCHEME = "gamma-android"

    /** What tells the server the app itself is claiming, not some page (routers/cloud_auth.py APP_CLAIM_HEADER). */
    const val CLAIM_HEADER = "X-Gamma-Desktop"
    const val CLAIM_VALUE = "claim"

    /** The loopback listener's address for app-start's `return_to`. */
    fun returnAddress(port: Int): String = "http://127.0.0.1:$port$RETURN_PATH"

    /** Where app-start is asked, on the page's server. */
    fun appStartUrl(origin: Origin): String = origin.string + APP_START_PATH

    /**
     * app-start's body: what the page's start asked for, and where to report.
     * `invite`, the e-mailed invitation that led to the start, goes along
     * when there is one (the server takes it; the iPad drops it).
     */
    data class AppStart(val next: String, val link: Boolean, val returnTo: String, val challenge: String, val invite: String = "") {
        /** The JSON body, keys sorted: challenge, invite (when any), link, next, return_to. */
        val json: String
            get() {
                val body = mutableMapOf<String, Any?>("next" to next, "link" to link, "return_to" to returnTo, "challenge" to challenge)
                if (invite.isNotEmpty()) body["invite"] = invite
                return Json.write(body, sortKeys = true)
            }

        companion object {
            /** From the page's start URL (`/api/auth/cloud/start?next=&link=1&invite=`). */
            fun from(start: String, returnTo: String, challenge: String): AppStart {
                val parts = UrlParts.parse(start)
                fun value(name: String) = parts?.queryValue(name).orEmpty()
                return AppStart(value("next").ifEmpty { "/" }, value("link").isNotEmpty(), returnTo, challenge, value("invite"))
            }
        }
    }

    /**
     * The account server's authorize URL from app-start's answer; null when
     * the server is older than the app's sign-in (no app-start route).
     * Anything else throws a [Refusal] with the server's `detail`, or the status.
     */
    fun authorizeUrl(status: Int, text: String): String? {
        if (status == 404 || status == 405) return null
        val json = Json.objectOrNull(text)
        val url = json?.get("url") as? String
        if (status in 200..299 && url != null && UrlParts.schemeOf(url).let { it == "https" || it == "http" }) return url
        throw Refusal(json?.get("detail") as? String ?: "HTTP $status")
    }

    /** The request that redeems a result: a form post, as the desktop's window sends it. */
    data class ClaimRequest(val url: String, val headers: Map<String, String>, val body: String) {
        val method: String get() = "POST"
    }

    fun claimRequest(origin: Origin, result: String, verifier: String): ClaimRequest = ClaimRequest(
        url = origin.string + CLAIM_PATH,
        headers = mapOf("Content-Type" to "application/x-www-form-urlencoded", CLAIM_HEADER to CLAIM_VALUE),
        body = formBody(listOf("result" to result, "verifier" to verifier)),
    )

    /** An x-www-form-urlencoded body with everything but the unreserved characters escaped (a space is %20). */
    fun formBody(fields: List<Pair<String, String>>): String =
        fields.joinToString("&") { (name, value) -> "$name=${percentEncode(value)}" }

    /**
     * What the browser brought back: its request's head ("GET
     * /cloud-signin?result=… HTTP/1.1"), or null for any other request (a
     * favicon, a probe). The server's urlencode writes a space as "+".
     */
    fun loopbackAnswer(head: String): CloudAnswer? {
        val parts = head.substringBefore("\r\n").split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[0] != "GET" || !parts[1].startsWith("/")) return null
        val url = UrlParts.parse("http://127.0.0.1" + parts[1].replace("+", "%20")) ?: return null
        if (url.path != RETURN_PATH) return null
        url.queryValue("result")?.takeIf { it.isNotEmpty() }?.let { return CloudAnswer.Result(it) }
        url.queryValue("error")?.takeIf { it.isNotEmpty() }?.let { return CloudAnswer.Error(it) }
        return null
    }

    /** The loopback's answer to the browser: on to the app's scheme, or nothing here. */
    fun loopbackResponse(answer: CloudAnswer?): ByteArray {
        val head = if (answer == null) {
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        } else {
            "HTTP/1.1 302 Found\r\nLocation: $CALLBACK_SCHEME://cloud-signin\r\nCache-Control: no-store\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
        }
        return head.toByteArray(Charsets.US_ASCII)
    }
}

/**
 * RFC 7636's S256: a random verifier only the shell keeps, and its
 * challenge, which the server stores with the pending sign-in.
 */
class Pkce(val verifier: String = newVerifier()) {
    val challenge: String get() = challenge(verifier)

    companion object {
        private val random = SecureRandom()

        /** 32 random bytes as unpadded base64url: 43 characters, as the server's challenge check expects. */
        fun newVerifier(): String = ByteArray(32).also { random.nextBytes(it) }.let(::base64Url)

        fun challenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))

        private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
