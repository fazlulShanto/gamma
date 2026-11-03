package com.gammapdf.gamma.server

import java.security.SecureRandom
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.gammapdf.gamma.app.Credentials

/** Where the seeded account's password is kept: KeystoreVault on the device, a map in the tests. */
interface PasswordVault {
    /** The stored password, or null: none stored, or one that cannot be read any more. */
    fun read(): String?

    /** False when it could not be stored. */
    fun write(password: String): Boolean
}

/**
 * The local server's one account, made once: "admin" and a random password
 * kept in the vault (ipad/GammaIPad/Server/LocalServer.swift LocalLogin,
 * the Keychain there). The shell signs in with it silently, as the desktop
 * does for its local servers.
 */
object LocalLogin {
    /**
     * For a first start: the stored account, or a new one stored now. Null
     * only when the vault refuses it: the server then seeds its own, and the
     * person signs in on the page with what its log says.
     */
    fun stored(vault: PasswordVault, random: Random = SecureRandom()): Credentials? {
        existing(vault)?.let { return it }
        val password = makePassword(random)
        return if (vault.write(password)) Credentials(Credentials.ACCOUNT, password) else null
    }

    /**
     * For a data directory that has its accounts already: the stored account,
     * never a new one, which that server would not know.
     */
    fun existing(vault: PasswordVault): Credentials? =
        vault.read()?.takeIf { it.isNotEmpty() }?.let { Credentials(Credentials.ACCOUNT, it) }

    /**
     * 12 base64url characters from 9 random bytes, like the desktop's
     * (desktop/lib/registry.js newPassword) and the server's own seed
     * (secrets.token_urlsafe(9)).
     */
    fun makePassword(random: Random = SecureRandom()): String = base64Url(ByteArray(9).also(random::nextBytes))

    /** base64url without padding (RFC 4648 §5). */
    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * A short text sealed with AES-GCM under a key that never leaves its store
 * (KeystoreVault's, in AndroidKeyStore), as one base64 string: the length
 * of the IV, the IV the cipher chose, then the ciphertext and its tag. A
 * text that does not open (another key's, as after a restore to a device
 * without that key, or one changed) is none.
 */
object Seal {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val body = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(byteArrayOf(iv.size.toByte()) + iv + body)
    }

    fun open(key: SecretKey, sealed: String): String? = try {
        val bytes = Base64.getDecoder().decode(sealed)
        val ivSize = bytes[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 1, ivSize))
        String(cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
