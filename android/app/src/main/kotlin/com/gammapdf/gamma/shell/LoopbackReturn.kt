package com.gammapdf.gamma.shell

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The loopback address the browser reports a Gamma Cloud sign-in to: a
 * socket on 127.0.0.1 at a free port, for one sign-in (the iPad's
 * LoopbackReturn in ipad/GammaIPad/Shell/CloudSignIn.swift). Each request
 * is answered from its first line (the rest of its head is read too, and
 * dropped): the sign-in's answer sends the browser on to
 * gamma-android://cloud-signin, anything else (a favicon) gets a 404. Only
 * the first answer counts; [onAnswer] has it
 * before the browser is sent on, so it is there when the app comes back.
 * Making one binds the socket, which reaches no network: the main thread
 * may.
 */
class LoopbackReturn(private val onAnswer: (CloudAnswer) -> Unit) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val answered = AtomicBoolean(false)

    /** app-start's `return_to`: http://127.0.0.1:<port>/cloud-signin. */
    val returnAddress: String = CloudWire.returnAddress(server.localPort)

    /**
     * Answers requests until [close], each on an IO thread of its own, so
     * a connection that never sends its request holds up no other.
     */
    suspend fun serve(): Unit = withContext(Dispatchers.IO) {
        while (true) {
            val connection = try {
                server.accept()
            } catch (e: IOException) {
                break // closed
            }
            launch { answer(connection) }
        }
    }

    override fun close() {
        try {
            server.close()
        } catch (e: IOException) {
            // closing is all that was asked
        }
    }

    private fun answer(connection: Socket) {
        try {
            connection.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val head = CloudFlow.requestHead(input)
                val answer = CloudWire.loopbackAnswer(head)
                if (head.endsWith("\r\n")) {
                    socket.soTimeout = REST_TIMEOUT_MS
                    try {
                        CloudFlow.skipHead(input, CloudFlow.HEAD_LIMIT)
                    } catch (e: SocketTimeoutException) {
                        // a client that sent its first line alone still gets its reply
                    }
                }
                if (answer != null && answered.compareAndSet(false, true)) onAnswer(answer)
                socket.getOutputStream().run {
                    write(CloudWire.loopbackResponse(answer))
                    flush()
                }
            }
        } catch (e: IOException) {
            // a request that never came whole, or a browser gone before the reply
        }
    }

    private companion object {
        /** How long a connection may take to send its first line. */
        const val READ_TIMEOUT_MS = 10_000

        /** How long the rest of its head may take, read only so the socket closes cleanly. */
        const val REST_TIMEOUT_MS = 2_000
    }
}
