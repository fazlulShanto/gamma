package com.gammapdf.gamma.json

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

// JSON as plain Kotlin values, the way the iPad's Swift handles [String: Any]:
// an object is a Map<String, Any?>, an array a List<Any?>, a number a Number,
// and null is null. Parsing goes through org.json (in android.jar; the JVM
// tests put the real library on the classpath); writing is done here, so the
// output is the same on both and keys can be sorted where a test pins a
// string (Bridge's HostInfo).
object Json {
    /** The value of [text], or null for JSON null; throws on malformed text. */
    fun parse(text: String): Any? = plain(JSONTokener(text).nextValue())

    /** The value of [text], or null when it is not JSON. */
    fun parseOrNull(text: String?): Any? = text?.let { runCatching { parse(it) }.getOrNull() }

    /** An object of [text], or null when it is not a JSON object. */
    @Suppress("UNCHECKED_CAST")
    fun objectOrNull(text: String?): Map<String, Any?>? = parseOrNull(text) as? Map<String, Any?>

    /** [value] as JSON text: maps, lists, arrays of numbers, strings, numbers,
     *  booleans and null. A non-finite number is written as null, as
     *  JSON.stringify does. */
    fun write(value: Any?, sortKeys: Boolean = false): String =
        StringBuilder().also { append(it, value, sortKeys) }.toString()

    private fun plain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val out = LinkedHashMap<String, Any?>()
            for (key in v.keys()) out[key] = plain(v.opt(key))
            out
        }
        is JSONArray -> List(v.length()) { plain(v.opt(it)) }
        else -> v
    }

    private fun append(sb: StringBuilder, v: Any?, sort: Boolean) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> number(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                val keys = v.keys.map { it.toString() }.let { if (sort) it.sorted() else it }
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    quote(sb, k)
                    sb.append(':')
                    append(sb, v[k], sort)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                v.forEachIndexed { i, item -> if (i > 0) sb.append(','); append(sb, item, sort) }
                sb.append(']')
            }
            is DoubleArray -> {
                sb.append('[')
                v.forEachIndexed { i, d -> if (i > 0) sb.append(','); number(sb, d) }
                sb.append(']')
            }
            is Array<*> -> append(sb, v.asList(), sort)
            else -> quote(sb, v.toString())
        }
    }

    // A whole number without a fraction (12, not 12.0), as JavaScript prints it.
    private fun number(sb: StringBuilder, d: Double) {
        when {
            !d.isFinite() -> sb.append("null")
            d == Math.rint(d) && kotlin.math.abs(d) < 1e15 -> sb.append(d.toLong())
            else -> sb.append(d.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

/** A number of a decoded JSON value as a Double, or null. */
fun Any?.jsonDouble(): Double? = (this as? Number)?.toDouble()

/** A finite number of a decoded JSON value, or null. */
fun Any?.jsonFinite(): Double? = jsonDouble()?.takeIf { it.isFinite() }

/** A whole number of a decoded JSON value (12 or 12.0), or null. */
fun Any?.jsonInt(): Int? = jsonFinite()?.takeIf { it == Math.rint(it) && kotlin.math.abs(it) <= Int.MAX_VALUE }?.toInt()

/** An object of a decoded JSON value, or null. */
@Suppress("UNCHECKED_CAST")
fun Any?.jsonObject(): Map<String, Any?>? = this as? Map<String, Any?>
