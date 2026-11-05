package com.gammapdf.gamma.shell

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * scheme://host:port, with the default port filled in, so a page's URL and
 * the origin a WebMessageListener reports compare equal when a browser would
 * say so (ipad/GammaIPad/Shell/Navigation.swift Origin). A port of 0 or
 * less is the default: WebKit reports it as 0, and android.net.Uri as -1.
 */
class Origin(scheme: String, host: String, port: Int? = null) {
    val scheme: String = scheme.lowercase(Locale.ROOT)

    /** An IPv6 address comes with brackets from a URL and without them from elsewhere. */
    val host: String = host.lowercase(Locale.ROOT).trim('[', ']')

    val port: Int = port?.takeIf { it > 0 } ?: defaultPort(this.scheme)

    /** "https://gamma.example.org": the port only when it is not the default. */
    val string: String
        get() {
            val shown = if (':' in host) "[$host]" else host
            return "$scheme://$shown" + if (port == defaultPort(scheme)) "" else ":$port"
        }

    override fun equals(other: Any?): Boolean =
        other is Origin && other.scheme == scheme && other.host == host && other.port == port

    override fun hashCode(): Int = (scheme.hashCode() * 31 + host.hashCode()) * 31 + port

    override fun toString(): String = string

    companion object {
        /** The origin of an absolute URL, or null when it has no host (about:, data:, mailto:). */
        fun of(url: String?): Origin? {
            val parts = url?.let { UrlParts.parse(it) } ?: return null
            val host = parts.host?.takeIf { it.isNotEmpty() } ?: return null
            return Origin(parts.scheme, host, parts.port)
        }

        private fun defaultPort(scheme: String) = if (scheme == "http") 80 else 443
    }
}

/**
 * Where a navigation goes, decided from its URL alone: the same rules as the
 * desktop shell's navigation guard (desktop/main.js) and the iPad's
 * (Navigation.swift Route). The server's pages stay, anything else leaves
 * the app's window.
 */
enum class Route {
    /** In the WebView. */
    LOAD,

    /** The page's Gamma Cloud sign-in start, which the shell takes over (CloudWire). */
    CLOUD_SIGN_IN,

    /** Another site: in a Custom Tab over the app. */
    BROWSER,

    /** mailto:, tel: and other apps' links: handed to the system. */
    SYSTEM;

    companion object {
        fun of(url: String, server: Origin?, mainFrame: Boolean): Route =
            when (UrlParts.schemeOf(url)) {
                "http", "https" -> when {
                    server != null && Origin.of(url) == server ->
                        if (UrlParts.parse(url)?.path?.trimEnd('/') == CloudWire.START_PATH) CLOUD_SIGN_IN else LOAD
                    // an embed inside the page (a video, a paper's preview) stays where it is
                    mainFrame -> BROWSER
                    else -> LOAD
                }
                "about", "blob", "data", "javascript" -> LOAD
                else -> SYSTEM
            }
    }
}

/**
 * The parts of an absolute URL that the shell reads: its scheme, the host
 * and port of its authority, its path, and its query's items. java.net.URI
 * refuses hosts a browser takes (an underscore in a NAS's name), so this
 * splits the text itself, the way URLComponents does on the iPad: the path
 * and the query's items percent-decoded, a "+" left as it is.
 */
internal class UrlParts private constructor(
    /** Lowercased. */
    val scheme: String,
    /** Without brackets for IPv6; null when the URL has no authority. */
    val host: String?,
    /** The port written in the URL, null when none is. */
    val port: Int?,
    /** Percent-decoded. */
    val path: String,
    /** As written, without its "?"; null when the URL has none. */
    val query: String?,
) {
    /** The query's items in order, each value null when it has no "=". */
    val queryItems: List<Pair<String, String?>> by lazy {
        query?.split('&')?.filter { it.isNotEmpty() }?.map { item ->
            val eq = item.indexOf('=')
            if (eq < 0) percentDecode(item) to null
            else percentDecode(item.substring(0, eq)) to percentDecode(item.substring(eq + 1))
        } ?: emptyList()
    }

    /** The first item named [name]'s value, as URLComponents.queryItems reads it. */
    fun queryValue(name: String): String? = queryItems.firstOrNull { it.first == name }?.second

    companion object {
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        // A registered name, ASCII or not (ServerAddress makes it ASCII); no space, no delimiter.
        private val HOST = Regex("^[A-Za-z0-9._~!$&'()*+,;=%\\u0080-\\uffff-]*$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+(%25[A-Za-z0-9._~-]+)?$")

        /** The lowercased scheme of [url], or null when it has none. */
        fun schemeOf(url: String): String? =
            SCHEME.find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

        /** [text]'s parts, or null when it is no absolute URL or its authority is malformed. */
        fun parse(text: String): UrlParts? {
            val scheme = schemeOf(text) ?: return null
            var rest = text.substring(scheme.length + 1).substringBefore('#')
            val query = if ('?' in rest) rest.substringAfter('?') else null
            rest = rest.substringBefore('?')
            if (!rest.startsWith("//")) return UrlParts(scheme, null, null, percentDecode(rest), query)
            rest = rest.substring(2)
            val slash = rest.indexOf('/')
            val authority = if (slash < 0) rest else rest.substring(0, slash)
            val path = if (slash < 0) "" else rest.substring(slash)
            val hostPort = authority.substringAfterLast('@')
            val host: String
            val portText: String
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                host = hostPort.substring(1, close)
                if (!IPV6.matches(host)) return null
                val after = hostPort.substring(close + 1)
                if (after.isNotEmpty() && !after.startsWith(":")) return null
                portText = after.removePrefix(":")
            } else {
                host = hostPort.substringBefore(':')
                portText = if (':' in hostPort) hostPort.substringAfter(':') else ""
                if (!HOST.matches(host)) return null
            }
            val port = when {
                portText.isEmpty() -> null
                portText.all { it in '0'..'9' } && portText.length <= 5 -> portText.toInt().takeIf { it <= 65535 } ?: return null
                else -> return null
            }
            return UrlParts(scheme, host, port, percentDecode(path), query)
        }

        /** %XX sequences as the UTF-8 bytes they spell; a "%" that starts none stays. */
        fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val out = ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length && hex(text[i + 1]) >= 0 && hex(text[i + 2]) >= 0) {
                    out.write(hex(text[i + 1]) * 16 + hex(text[i + 2]))
                    i += 3
                } else {
                    val end = if (Character.isHighSurrogate(c) && i + 1 < text.length) i + 2 else i + 1
                    out.write(text.substring(i, end).toByteArray(Charsets.UTF_8))
                    i = end
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private fun hex(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
