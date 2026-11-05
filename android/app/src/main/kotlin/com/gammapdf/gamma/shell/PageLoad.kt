package com.gammapdf.gamma.shell

/**
 * A main-frame load that failed, as the launcher's failure says it
 * (AppModel.loadFailed, ShellFailure.unreachable): the sentence the iPad's
 * URL loading system gives for the same failure, with Chromium's code after
 * it, from a WebViewClient error (Navigation). Pure, for the JVM tests.
 */
object PageLoad {
    // android.webkit.WebViewClient's codes (PageLoadTest holds them to android.jar's).
    const val ERROR_HOST_LOOKUP = -2
    const val ERROR_CONNECT = -6
    const val ERROR_IO = -7
    const val ERROR_TIMEOUT = -8
    const val ERROR_REDIRECT_LOOP = -9
    const val ERROR_UNSUPPORTED_SCHEME = -10
    const val ERROR_FAILED_SSL_HANDSHAKE = -11
    const val ERROR_BAD_URL = -12

    const val INSECURE = "An SSL error has occurred and a secure connection to the server cannot be made."

    /**
     * What the launcher says for a failure with WebViewClient's [code] and
     * Chromium's [description] ("net::ERR_CONNECTION_REFUSED"), or null when
     * it is no failure: a load given up because another took its place or
     * the shell sent it elsewhere (the iPad's isInterruption).
     */
    fun failure(code: Int, description: String?): String? {
        val detail = description?.trim().orEmpty()
        if ("ERR_ABORTED" in detail || "ERR_CANCELLED" in detail) return null
        val sentence = when {
            "ERR_INTERNET_DISCONNECTED" in detail -> "The Internet connection appears to be offline."
            code == ERROR_HOST_LOOKUP -> "A server with the specified hostname could not be found."
            code == ERROR_CONNECT -> "Could not connect to the server."
            code == ERROR_TIMEOUT -> "The request timed out."
            code == ERROR_IO -> "The network connection was lost."
            code == ERROR_REDIRECT_LOOP -> "Too many HTTP redirects."
            code == ERROR_FAILED_SSL_HANDSHAKE -> INSECURE
            code == ERROR_BAD_URL || code == ERROR_UNSUPPORTED_SCHEME -> "The address is not one Gamma can load."
            else -> null
        }
        return when {
            sentence == null -> detail.ifEmpty { "The page did not load." }
            detail.isEmpty() -> sentence
            else -> "$sentence ($detail)"
        }
    }
}
