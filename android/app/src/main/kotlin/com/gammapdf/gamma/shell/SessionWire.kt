package com.gammapdf.gamma.shell

import com.gammapdf.gamma.app.Credentials
import com.gammapdf.gamma.json.Json

/**
 * The server's side of the shell's session read and silent sign-in
 * (Session), as the desktop's refreshGamma and autoLoginScript and the
 * iPad's WebShell scripts read it, made pure for the JVM tests. Android's
 * WebView has no content world of the app's own, so these run natively with
 * the page's cookie rather than in the page, which then never sees the
 * password.
 */
object SessionWire {
    const val SESSION_PATH = "/api/session"
    const val LOGIN_PATH = "/api/login"

    /**
     * What the bar's switcher reads of an /api/session answer: `{user,
     * default_workspace, workspaces}`, or null when no one is signed in or
     * the answer is not one (the iPad's sessionScript: a falsy user is no
     * session, a missing default workspace is null, missing workspaces are
     * none).
     */
    fun session(status: Int, body: String?): Map<String, Any?>? {
        if (status !in 200..299) return null
        val answer = Json.objectOrNull(body) ?: return null
        val user = answer["user"]
        if (!truthy(user)) return null
        val workspaces = answer["workspaces"]
        return linkedMapOf(
            "user" to user,
            "default_workspace" to answer["default_workspace"],
            "workspaces" to if (truthy(workspaces)) workspaces else emptyList<Any?>(),
        )
    }

    /** The /api/login body for the seeded account. */
    fun loginBody(login: Credentials): String =
        Json.write(linkedMapOf("username" to login.username, "password" to login.password))

    /**
     * The Set-Cookie headers of an answer (HttpURLConnection's
     * headerFields, whose names keep the server's case and whose status line
     * has a null name).
     */
    fun setCookies(headers: Map<String?, List<String>?>): List<String> =
        headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value.orEmpty() }

    /** JavaScript's truthiness of a decoded JSON value, which the iPad's scripts test. */
    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> value.isNotEmpty()
        else -> true
    }
}
