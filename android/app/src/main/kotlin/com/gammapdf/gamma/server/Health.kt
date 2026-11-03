package com.gammapdf.gamma.server

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import com.gammapdf.gamma.json.Json

/**
 * Whether a Gamma server is up, by the desktop's rule (desktop/lib/sidecar.js
 * healthy; ipad/GammaIPad/Server/LocalServer.swift Health): /api/health
 * answers 200, or 503 with `data_directory_not_upgradable`, a server that is
 * up and shows its own guidance on every page.
 */
object Health {
    /** What of an answer is read: the health body is a few bytes. */
    private const val BODY_LIMIT = 64 * 1024

    fun isUp(status: Int, body: ByteArray): Boolean {
        if (status in 200..299) return true
        if (status != 503) return false
        return Json.objectOrNull(String(body, Charsets.UTF_8))?.get("error") == "data_directory_not_upgradable"
    }

    /** GET <server>/api/health, uncached, within [timeoutMs] to connect and as much to read. Blocking. */
    fun probe(server: String, timeoutMs: Int): Boolean {
        val connection = try {
            URI("$server/api/health").toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return false
        }
        return try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            isUp(status, stream?.use { read(it) } ?: ByteArray(0))
        } catch (e: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun read(stream: InputStream): ByteArray {
        val buffer = ByteArray(BODY_LIMIT)
        var count = 0
        while (count < buffer.size) {
            val n = stream.read(buffer, count, buffer.size - count)
            if (n < 0) break
            count += n
        }
        return buffer.copyOf(count)
    }
}
