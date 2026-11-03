package com.gammapdf.gamma.shell

import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loopback the browser reports a sign-in to (shell/LoopbackReturn.kt),
 * over real sockets: what the iPad's LoopbackReturn answers, and the
 * Android listener's own guarantees (a request in pieces, one slow
 * connection holding up no other, close ending it).
 */
class LoopbackReturnTest {
    private val loopbackHost = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun exchange(port: Int, vararg pieces: String): String = Socket(loopbackHost, port).use { socket ->
        socket.soTimeout = 5000
        val out = socket.getOutputStream()
        for (piece in pieces) {
            out.write(piece.toByteArray(Charsets.UTF_8))
            out.flush()
            Thread.sleep(30)
        }
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun port(loopback: LoopbackReturn): Int =
        Regex("^http://127\\.0\\.0\\.1:(\\d+)/cloud-signin$").find(loopback.returnAddress)!!.groupValues[1].toInt()

    @Test
    fun theFirstAnswerIsKeptAndTheBrowserSentToTheApp() = runBlocking {
        val answers = Collections.synchronizedList(ArrayList<CloudAnswer>())
        LoopbackReturn { answers += it }.use { loopback ->
            val serving = launch(Dispatchers.IO) { loopback.serve() }
            val port = port(loopback)

            val favicon = exchange(port, "GET /favicon.ico HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertEquals("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", favicon)
            assertTrue(answers.isEmpty())

            val result = exchange(port, "GET /cloud-signin?res", "ult=abc+def&x=1 HTTP/1.1\r\n", "Host: 127.0.0.1\r\n\r\n")
            assertEquals("HTTP/1.1 302 Found\r\nLocation: gamma-android://cloud-signin\r\nCache-Control: no-store\r\n" +
                             "Content-Length: 0\r\nConnection: close\r\n\r\n", result)
            assertEquals(listOf(CloudAnswer.Result("abc def")), answers.toList())

            val late = exchange(port, "GET /cloud-signin?error=Sign-in+cancelled. HTTP/1.1\r\n\r\n")
            assertTrue(late.startsWith("HTTP/1.1 302 Found\r\n"))
            assertEquals("only the first answer counts", listOf(CloudAnswer.Result("abc def")), answers.toList())

            loopback.close()
            withTimeout(5000) { serving.join() }
        }
    }

    @Test
    fun aSilentConnectionHoldsUpNoOther() = runBlocking {
        LoopbackReturn { }.use { loopback ->
            val serving = launch(Dispatchers.IO) { loopback.serve() }
            val port = port(loopback)
            Socket(loopbackHost, port).use {
                val started = System.nanoTime()
                assertTrue(exchange(port, "GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404 Not Found"))
                assertTrue("answered while the other waits", System.nanoTime() - started < 3_000_000_000L)
            }
            loopback.close()
            withTimeout(15_000) { serving.join() }
        }
    }

    @Test
    fun anErrorIsKeptToo() = runBlocking {
        val answers = Collections.synchronizedList(ArrayList<CloudAnswer>())
        LoopbackReturn { answers += it }.use { loopback ->
            val serving = launch(Dispatchers.IO) { loopback.serve() }
            exchange(port(loopback), "GET /cloud-signin?error=This+account+is+not+invited. HTTP/1.1\r\n\r\n")
            assertEquals(listOf(CloudAnswer.Error("This account is not invited.")), answers.toList())
            loopback.close()
            withTimeout(5000) { serving.join() }
        }
    }
}
