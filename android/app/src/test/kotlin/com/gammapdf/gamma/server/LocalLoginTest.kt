package com.gammapdf.gamma.server

import java.util.Base64
import java.util.Random
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import com.gammapdf.gamma.app.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The seeded account over a vault (the Keychain's rules in LocalServer.swift LocalLogin), and the seal KeystoreVault keeps it in. */
class LocalLoginTest {
    private class MemoryVault(var password: String? = null, val refuses: Boolean = false) : PasswordVault {
        var writes = 0

        override fun read(): String? = password

        override fun write(password: String): Boolean {
            writes++
            if (refuses) return false
            this.password = password
            return true
        }
    }

    @Test
    fun aFirstStartMakesThePasswordOnceAndKeepsIt() {
        val vault = MemoryVault()
        val made = LocalLogin.stored(vault, Random(1))!!
        assertEquals(Credentials.ACCOUNT, made.username)
        assertEquals(12, made.password.length)
        assertEquals(made.password, vault.password)
        assertEquals("a stored password is the account's from then on", made, LocalLogin.stored(vault, Random(2)))
        assertEquals(1, vault.writes)
    }

    @Test
    fun aVaultThatRefusesGivesNoAccount() {
        assertNull(LocalLogin.stored(MemoryVault(refuses = true)))
    }

    @Test
    fun aUsedDataDirectoryGetsOnlyAStoredPassword() {
        val empty = MemoryVault()
        assertNull(LocalLogin.existing(empty))
        assertEquals("never a new one, which that server would not know", 0, empty.writes)
        assertNull(LocalLogin.existing(MemoryVault("")))
        assertEquals(Credentials("admin", "kept-pw"), LocalLogin.existing(MemoryVault("kept-pw")))
    }

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun aSealedPasswordOpensWithItsKeyOnly() {
        val key = key()
        val sealed = Seal.seal(key, "abcdefghijkl")
        assertEquals("abcdefghijkl", Seal.open(key, sealed))
        assertNotEquals("a fresh IV each time", sealed, Seal.seal(key, "abcdefghijkl"))
        assertNull("another key's, as after a restore to another device", Seal.open(key(), sealed))
    }

    @Test
    fun aChangedOrBrokenSealIsNone() {
        val key = key()
        val bytes = Base64.getDecoder().decode(Seal.seal(key, "abcdefghijkl"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(Seal.open(key, Base64.getEncoder().encodeToString(bytes)))
        assertNull(Seal.open(key, "not base64 at all!"))
        assertNull(Seal.open(key, ""))
        assertNull(Seal.open(key, Base64.getEncoder().encodeToString(byteArrayOf(12, 1, 2))))
    }
}
