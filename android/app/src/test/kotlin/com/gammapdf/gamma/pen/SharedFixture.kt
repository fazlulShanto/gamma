package com.gammapdf.gamma.pen

import com.gammapdf.gamma.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import java.io.File

/**
 * A file of the repository's tests/shared, the rows the web's, the server's
 * and the iPad's tests read too, from the repository the build names
 * (`gamma.repo`, app/build.gradle.kts).
 */
internal object SharedFixture {
    @Suppress("UNCHECKED_CAST")
    fun load(name: String): Map<String, Any?> {
        val repo = System.getProperty("gamma.repo") ?: error("gamma.repo is not set: run the tests through Gradle")
        val file = File(repo, "tests/shared/$name.json")
        check(file.isFile) { "no $file" }
        return Json.parse(file.readText(Charsets.UTF_8)) as Map<String, Any?>
    }

    /**
     * A fixture's sample, an array (`[x, y]`, `[x, y, t]`, or the host's
     * `[x, y, p, t, …]`, null as NaN) or an object (`{x, y, p?, t?}`), as the
     * host sends it: `[x, y, p, t, …]` (ipad/GammaIPadTests/PencilTests.swift FixtureSample).
     */
    fun hostSample(v: Any?): DoubleArray {
        if (v is List<*>) {
            val values = v.map { (it as? Number)?.toDouble() ?: Double.NaN }
            return when (values.size) {
                0, 1 -> error("not a sample: $v")
                2 -> doubleArrayOf(values[0], values[1], 0.5, 0.0)
                3 -> doubleArrayOf(values[0], values[1], 0.5, values[2])
                else -> values.toDoubleArray()
            }
        }
        val m = v as? Map<*, *> ?: error("not a sample: $v")
        return doubleArrayOf(num(m["x"]), num(m["y"]), (m["p"] as? Number)?.toDouble() ?: 0.5, (m["t"] as? Number)?.toDouble() ?: 0.0)
    }

    fun num(v: Any?): Double = (v as? Number)?.toDouble() ?: error("not a number: $v")

    fun params(v: Any?): Map<String, Double>? =
        (v as? Map<*, *>)?.entries?.associate { (k, value) -> k as String to num(value) }

    fun list(v: Any?): List<*> = v as? List<*> ?: error("not a list: $v")

    @Suppress("UNCHECKED_CAST")
    fun rows(v: Any?): List<Map<String, Any?>> = list(v).map { it as Map<String, Any?> }
}

/** `got` within `accuracy` of `want`, value for value, or both null. */
internal fun assertNear(want: DoubleArray?, got: DoubleArray?, note: String, accuracy: Double = 0.0005) {
    if (want == null) {
        assertNull("$note: ${got?.toList()}, where none was expected", got)
        return
    }
    assertNotNull("$note: none, where ${want.toList()} was expected", got)
    if (got!!.size != want.size) fail("$note: ${got.toList()}, where ${want.toList()} was expected")
    for (i in want.indices) assertEquals("$note: sample $i", want[i], got[i], accuracy)
}

/** Two lists of samples with the same values. */
internal fun assertSamples(want: List<DoubleArray>, got: List<DoubleArray>, note: String = "") {
    assertEquals("$note: ${got.map { it.toList() }}", want.map { it.toList() }, got.map { it.toList() })
}

/** Shape params: the same keys, `n` exact, the rest within 0.011 (a refit is on the 0.01 grid); or both null. */
internal fun assertParams(want: Map<String, Double>?, got: Map<String, Double>?, note: String) {
    if (want == null) {
        assertNull("$note: the shape is dropped", got)
        return
    }
    assertNotNull("$note: dropped, where $want was expected", got)
    assertEquals(note, want.keys, got!!.keys)
    for ((key, value) in want) {
        val mine = got[key] ?: Double.NaN
        if (key == "n") assertEquals("$note: n", value, mine, 0.0) else assertEquals("$note: $key", value, mine, 0.011)
    }
}
