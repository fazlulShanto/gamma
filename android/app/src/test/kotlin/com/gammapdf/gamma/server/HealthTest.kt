package com.gammapdf.gamma.server

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ipad/GammaIPadTests/ServerTests.swift HealthTests, and the probe over a socket. */
class HealthTest {
    @Test
    fun upIsOKOrAServerThatCannotUpgradeItsData() {
        assertTrue(Health.isUp(200, """{"ok":true}""".toByteArray()))
        assertTrue(Health.isUp(503, """{"error":"data_directory_not_upgradable","build":"x"}""".toByteArray()))
    }

    @Test
    fun anythingElseIsDown() {
        assertFalse(Health.isUp(503, """{"error":"busy"}""".toByteArray()))
        assertFalse(Health.isUp(503, "<html>".toByteArray()))
        assertFalse(Health.isUp(500, ByteArray(0)))
        assertFalse(Health.isUp(404, ByteArray(0)))
    }

    @Test
    fun theProbeReadsTheAnswer() {
        answering("200 OK", """{"ok":true}""") { assertTrue(Health.probe(it, 1500)) }
        answering("503 Service Unavailable", """{"error":"data_directory_not_upgradable"}""") { assertTrue(Health.probe(it, 1500)) }
        answering("503 Service Unavailable", """{"error":"busy"}""") { assertFalse(Health.probe(it, 1500)) }
        answering("500 Internal Server Error", "") { assertFalse(Health.probe(it, 1500)) }
    }

    @Test
    fun nothingListeningIsDown() {
        val port = LocalPorts.anyFree()!!
        assertFalse(Health.probe("http://127.0.0.1:$port", 1500))
        assertFalse(Health.probe("not a url", 1500))
    }

    /** A server on a free loopback port that answers every request with [status] and [body], while [check] runs. */
    private fun answering(status: String, body: String, check: (String) -> Unit) {
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0))
            val serving = thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val bytes = body.toByteArray()
                        it.getOutputStream().write(
                            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray() + bytes,
                        )
                    }
                }
            }
            check("http://127.0.0.1:${server.localPort}")
            server.close()
            serving.join(2000)
        }
    }
}
