package com.gammapdf.gamma.shell

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.Locale
import kotlin.concurrent.thread

/**
 * An HTTP server on a free loopback port for the shell's request tests:
 * each request gets what [respond] makes of it, its connection closed
 * after, and is kept in [requests].
 */
class FakeHttp(private val respond: (Request) -> Response) : Closeable {
    data class Request(val method: String, val target: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    }

    class Response(val status: Int, val headers: List<Pair<String, String>> = emptyList(), val body: String = "") {
        fun bytes(): ByteArray {
            val content = body.toByteArray(Charsets.UTF_8)
            val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
            for ((name, value) in headers) head.append("$name: $value\r\n")
            head.append("Content-Length: ${content.size}\r\nConnection: close\r\n\r\n")
            return head.toString().toByteArray(Charsets.UTF_8) + content
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            302 -> "Found"
            303 -> "See Other"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Status"
        }
    }

    private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val origin: String = "http://127.0.0.1:${server.localPort}"
    val requests: MutableList<Request> = Collections.synchronizedList(ArrayList())

    private val worker = thread(isDaemon = true, name = "fake-http") {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            socket.use {
                try {
                    val request = read(BufferedInputStream(it.getInputStream()))
                    requests += request
                    it.getOutputStream().apply {
                        write(respond(request).bytes())
                        flush()
                    }
                } catch (e: IOException) {
                    // a client gone early
                }
            }
        }
    }

    override fun close() {
        server.close()
        worker.join(2000)
    }

    private fun read(input: InputStream): Request {
        val head = ArrayList<String>()
        while (true) {
            val line = line(input) ?: break
            if (line.isEmpty()) break
            head += line
        }
        val (method, target) = head.first().split(' ').let { it[0] to it[1] }
        val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase(Locale.ROOT) to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val n = input.read(body, filled, length - filled)
            if (n < 0) break
            filled += n
        }
        return Request(method, target, headers, String(body, 0, filled, Charsets.UTF_8))
    }

    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").removeSuffix("\r")
            out.write(b)
        }
    }
}
