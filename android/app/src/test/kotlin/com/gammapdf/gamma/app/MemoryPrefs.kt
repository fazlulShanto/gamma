package com.gammapdf.gamma.app

/** Prefs over a map, for the tests: what AndroidPrefs keeps in SharedPreferences. */
class MemoryPrefs : Prefs {
    val values = HashMap<String, Any>()

    override fun string(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun bool(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    override fun putBool(key: String, value: Boolean) {
        values[key] = value
    }

    override fun int(key: String): Int? = values[key] as? Int

    override fun putInt(key: String, value: Int?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
