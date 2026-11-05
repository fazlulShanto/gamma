package com.gammapdf.gamma.shell

import com.gammapdf.gamma.app.Server
import com.gammapdf.gamma.json.Json

/**
 * A server that would not open, as the launcher shows it (launcher.html
 * showFailure, loaded with `?error=`), in the shape the desktop sends
 * (ipad/GammaIPad/App/ShellState.swift ShellFailure). The launcher's Try
 * again calls `open(server)`; its Show log file, offered only with a `log`,
 * calls `revealLog`.
 */
data class ShellFailure(val summary: String, val detail: String, val log: String? = null, val server: String) {
    val action: String get() = "retry"

    /** The JSON the launcher reads, its keys in the iPad's order; no `log` when there is none. */
    val json: String
        get() {
            val out = LinkedHashMap<String, Any?>()
            out["summary"] = summary
            out["detail"] = detail
            if (log != null) out["log"] = log
            out["action"] = action
            out["server"] = server
            return Json.write(out)
        }

    companion object {
        /** The shell's page could not load from the server at [url]. */
        fun unreachable(url: String, server: String, detail: String): ShellFailure {
            val host = UrlParts.parse(url)?.host?.takeIf { it.isNotEmpty() } ?: url
            return ShellFailure("Gamma cannot reach $host", detail, server = server)
        }

        /** This device's server did not start, with the end of its log. */
        fun localFailed(detail: String, log: String): ShellFailure =
            ShellFailure("Gamma could not start on this device", detail, log.ifEmpty { null }, Server.Local.id)
    }
}

/**
 * The desktop's chrome pages, desktop/ui/bar.html and launcher.html, as the
 * app serves them: bundled under assets/chrome/ (build.gradle.kts
 * bundleWebAssets) and loaded through WebViewAssetLoader from its own https
 * origin, which nothing else uses (docs/dev/android.md "The chrome").
 */
object ChromePages {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val ROOT = "$ORIGIN/assets/chrome/"
    const val BAR = "${ROOT}bar.html"
    const val LAUNCHER = "${ROOT}launcher.html"

    /** The refusal a call from anything but a chrome page gets. */
    const val ONLY_OUR_PAGES = "Only Gamma's own pages talk to the app."

    /**
     * The launcher, with the failure it shows as the desktop passes it.
     * Every character but the unreserved ones is percent-encoded: the
     * page's URLSearchParams reads a "+" as a space.
     */
    fun launcher(error: String?): String = if (error == null) LAUNCHER else "$LAUNCHER?error=${percentEncode(error)}"

    /**
     * Only the bundled pages call: the main frame of a page under
     * [ROOT]; a frame inside one is not the page.
     */
    fun accepts(url: String?, mainFrame: Boolean): Boolean {
        if (!mainFrame || url == null) return false
        val parts = UrlParts.parse(url) ?: return false
        return Origin.of(url) == Origin.of(ORIGIN) && parts.path.startsWith("/assets/chrome/")
    }
}

/**
 * A refusal with the sentence shown for it: a chrome call's, which the
 * page's promise rejects with, or the Gamma Cloud sign-in's (CloudWire).
 */
class Refusal(message: String) : Exception(message)

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

/**
 * [text] with every byte of its UTF-8 but A–Z a–z 0–9 - . _ ~ written as
 * %XX (uppercase), as the iPad's addingPercentEncoding with those allowed:
 * what a query value and a form field need whatever reads them.
 */
internal fun percentEncode(text: String): String {
    val out = StringBuilder(text.length)
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xff
        if (b < 0x80 && b.toChar() in UNRESERVED) out.append(b.toChar())
        else out.append('%').append("0123456789ABCDEF"[b shr 4]).append("0123456789ABCDEF"[b and 15])
    }
    return out.toString()
}
