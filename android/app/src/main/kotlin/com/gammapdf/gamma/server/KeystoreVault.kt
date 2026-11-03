package com.gammapdf.gamma.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The seeded account's password, sealed ([Seal]) with an AES-GCM key that
 * lives in AndroidKeyStore and kept in SharedPreferences file [name], where
 * the iPad keeps it in the Keychain (ipad/GammaIPad/Server/Keychain.swift).
 * The key never leaves the device's keystore and is in no backup or
 * transfer, so a restored file opens to nothing: no password, the iPad's
 * case of a backup restored without its Keychain. A plain file would hand
 * the password to anything that reads the app's backup; the deprecated
 * EncryptedSharedPreferences is not needed for one value. Blocking (the
 * keystore, a synchronous commit): off the main thread.
 */
class KeystoreVault(context: Context, private val name: String = "gamma.local-login") : PasswordVault {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** The key's alias in AndroidKeyStore, the file's name. */
    private val alias = name

    override fun read(): String? = try {
        val sealed = prefs.getString(KEY, null)
        val key = keyStore().getKey(alias, null) as? SecretKey
        if (sealed == null || key == null) null else Seal.open(key, sealed)
    } catch (e: Exception) {
        null
    }

    override fun write(password: String): Boolean = try {
        val store = keyStore()
        val sealed = try {
            Seal.seal(key(store), password)
        } catch (e: Exception) {
            // A key the keystore no longer uses: a new one, once.
            store.deleteEntry(alias)
            Seal.seal(key(store), password)
        }
        prefs.edit().putString(KEY, sealed).commit()
    } catch (e: Exception) {
        false
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    private fun key(store: KeyStore): SecretKey {
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val KEY = "admin-password"
    }
}
