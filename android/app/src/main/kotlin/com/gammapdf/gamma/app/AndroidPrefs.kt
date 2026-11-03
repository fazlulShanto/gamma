package com.gammapdf.gamma.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Prefs over the app's SharedPreferences file "gamma". Writes are applied
 * at once in memory and reach the disk in the background, so they suit the
 * main thread. A key holding another type reads as absent.
 */
class AndroidPrefs(private val shared: SharedPreferences) : Prefs {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    override fun string(key: String): String? = read { shared.getString(key, null) }

    override fun putString(key: String, value: String?) =
        shared.edit { if (value == null) remove(key) else putString(key, value) }

    override fun bool(key: String, default: Boolean): Boolean = read { shared.getBoolean(key, default) } ?: default

    override fun putBool(key: String, value: Boolean) = shared.edit { putBoolean(key, value) }

    override fun int(key: String): Int? = read { if (shared.contains(key)) shared.getInt(key, 0) else null }

    override fun putInt(key: String, value: Int?) =
        shared.edit { if (value == null) remove(key) else putInt(key, value) }

    private fun <T> read(get: () -> T): T? = try {
        get()
    } catch (e: ClassCastException) {
        null
    }

    companion object {
        const val FILE = "gamma"
    }
}
