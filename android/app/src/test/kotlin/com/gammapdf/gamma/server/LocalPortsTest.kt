package com.gammapdf.gamma.server

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ipad/GammaIPadTests/ServerTests.swift LocalPortsTests. */
class LocalPortsTest {
    @Test
    fun theRememberedPortWhileItIsFree() {
        assertEquals(50123, LocalPorts.pick(50123, 0) { true })
    }

    @Test
    fun aTakenOrMissingPortGivesOneFromTheDynamicRange() {
        assertEquals("the search starts at the random offset", LocalPorts.FIRST + 7, LocalPorts.pick(50123, 7) { it != 50123 })
        assertEquals(LocalPorts.FIRST, LocalPorts.pick(0, 0) { true })
        assertEquals("a remembered value that is no port is ignored", LocalPorts.FIRST, LocalPorts.pick(70000, 0) { true })
    }

    @Test
    fun theSearchWrapsAroundTheRange() {
        val last = LocalPorts.FIRST + LocalPorts.COUNT - 1
        assertEquals(last, LocalPorts.pick(0, LocalPorts.COUNT - 1) { it == last || it == LocalPorts.FIRST })
        assertEquals(LocalPorts.FIRST, LocalPorts.pick(0, LocalPorts.COUNT - 1) { it == LocalPorts.FIRST })
    }

    @Test
    fun noFreePortIsNull() {
        assertNull(LocalPorts.pick(50123, 0) { false })
    }

    @Test
    fun theSystemHandsOutAPortAndZeroIsNone() {
        val port = LocalPorts.anyFree()
        assertNotNull(port)
        assertTrue(port!! in 1..65535)
        assertFalse(LocalPorts.isFree(0))
        assertFalse(LocalPorts.isFree(70000))
    }

    @Test
    fun aPortSomeoneListensOnIsTaken() {
        ServerSocket().use { listening ->
            listening.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0))
            assertFalse(LocalPorts.isFree(listening.localPort))
            assertTrue("the rule moves on from it", LocalPorts.choose(listening.localPort) != listening.localPort)
        }
    }

    @Test
    fun theWholeRuleKeepsAFreeRememberedPort() {
        val free = LocalPorts.anyFree()!!
        assertEquals(free, LocalPorts.choose(free))
    }
}
