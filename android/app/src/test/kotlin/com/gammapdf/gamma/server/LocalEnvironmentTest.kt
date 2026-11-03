package com.gammapdf.gamma.server

import com.gammapdf.gamma.app.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ipad/GammaIPadTests/ServerTests.swift LocalEnvironmentTests. */
class LocalEnvironmentTest {
    private fun environment(seed: Credentials?) = LocalEnvironment.make(
        dataDirectory = "/data",
        staticDirectory = "/files/frontend_dist",
        tempDirectory = "/cache/gamma-tmp",
        version = "0.2.0",
        port = 50123,
        seed = seed,
    )

    @Test
    fun theServersEnvironment() {
        val env = environment(seed = null)
        assertEquals("/data", env["GAMMA_DATA_DIR"])
        assertEquals("/files/frontend_dist", env["GAMMA_STATIC_DIR"])
        assertEquals("0.2.0", env["GAMMA_VERSION"])
        assertEquals("50123", env["GAMMA_PORT"])
        assertEquals("https://account.gammapdf.com", env["GAMMA_CLOUD_DEFAULT_ISSUER"])
        assertEquals("/cache/gamma-tmp", env["TMPDIR"])
        assertNull(env["GAMMA_ADMIN_USER"])
        assertNull(env["GAMMA_ADMIN_PASSWORD"])
        assertFalse("gamma_mobile adds the apps' defaults itself", "GAMMA_UPDATES" in env)
    }

    @Test
    fun theSeedOnlyForAnEmptyDataDirectory() {
        val env = environment(seed = Credentials("admin", "abcdefghijkl"))
        assertEquals("admin", env["GAMMA_ADMIN_USER"])
        assertEquals("abcdefghijkl", env["GAMMA_ADMIN_PASSWORD"])
    }

    @Test
    fun thePasswordIsTwelveBase64URLCharacters() {
        val password = LocalLogin.makePassword()
        assertEquals(12, password.length)
        assertTrue(password.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
        assertEquals("-_-_", LocalLogin.base64Url(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte())))
    }
}
