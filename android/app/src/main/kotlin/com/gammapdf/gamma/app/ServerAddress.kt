package com.gammapdf.gamma.app

import java.net.IDN
import java.util.Locale
import com.gammapdf.gamma.shell.UrlParts

/** What the address field accepts (ipad/GammaIPad/App/AppModel.swift ServerAddress). */
object ServerAddress {
    /**
     * The server's origin from what a person types or pastes: a bare host
     * means https, and a page link keeps only its origin
     * (`scheme://host[:port]`, the host lowercased and in ASCII, as the
     * WebView reports origins). The web app is served at the root, and the
     * Gamma Cloud sign-in works by origin, so a path would only get in the
     * way. Null for anything but an http or https address with a host.
     */
    fun parse(text: String): String? {
        var address = text.trim()
        if (address.isEmpty()) return null
        if ("://" !in address) address = "https://$address"
        val parts = UrlParts.parse(address) ?: return null
        if (parts.scheme != "http" && parts.scheme != "https") return null
        val host = parts.host?.takeIf { it.isNotEmpty() } ?: return null
        val ascii = if (':' in host) {
            "[${host.lowercase(Locale.ROOT)}]"
        } else {
            try {
                IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
            } catch (e: IllegalArgumentException) {
                return null
            }.ifEmpty { return null }
        }
        return "${parts.scheme}://$ascii" + (parts.port?.let { ":$it" } ?: "")
    }
}
