package com.gammapdf.gamma.app

/**
 * What the app keeps between launches, by key, as the iPad keeps it in
 * UserDefaults: an interface, so the model is tested over a map
 * (AndroidPrefs is SharedPreferences). Setting a nullable value to null
 * removes the key.
 */
interface Prefs {
    fun string(key: String): String?
    fun putString(key: String, value: String?)
    fun bool(key: String, default: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
    fun int(key: String): Int?
    fun putInt(key: String, value: Int?)
}
