package com.gammapdf.gamma.server

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.random.Random

/**
 * The port rule of desktop/lib/sidecar.js pickPort, as the iPad keeps it
 * (ipad/GammaIPad/Server/LocalServer.swift LocalPorts): the port the server
 * had last time while it is free, so the address (and what the web app keeps
 * per origin) stays the same across launches; else a free one from the IANA
 * dynamic range, from a random start. The checks bind sockets: off the main
 * thread.
 */
object LocalPorts {
    const val FIRST = 49152
    const val COUNT = 16384

    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun pick(remembered: Int, from: Int, isFree: (Int) -> Boolean): Int? {
        if (remembered in 1..65535 && isFree(remembered)) return remembered
        for (i in 0 until COUNT) {
            val port = FIRST + Math.floorMod(from + i, COUNT)
            if (isFree(port)) return port
        }
        return null
    }

    /** The whole rule: [pick] from a random start, else any port the system hands out. */
    fun choose(remembered: Int): Int? = pick(remembered, Random.nextInt(COUNT), ::isFree) ?: anyFree()

    /**
     * Whether uvicorn could listen on 127.0.0.1:[port] now: a bind with the
     * SO_REUSEADDR uvicorn sets too, so a port whose last connections are
     * still in TIME_WAIT (the app's previous run, just ended) counts as free
     * and keeps its address, while one another socket listens on does not
     * (Linux refuses a second bind to a listening address).
     */
    fun isFree(port: Int): Boolean = port > 0 && bind(port) != null

    /** Any port the system hands out, the desktop's last resort. */
    fun anyFree(): Int? = bind(0)

    /** Binds a throwaway socket to 127.0.0.1:[port] and answers the port it got (the system's pick for 0), or null. */
    private fun bind(port: Int): Int? {
        if (port !in 0..65535) return null
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(loopback, port))
                socket.localPort
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }
}
