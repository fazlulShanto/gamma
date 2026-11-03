package com.gammapdf.gamma.pen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The brush table's port (InkWidth) against every row of
 * tests/shared/inkwidth.json, the rows frontend/tests/finishStroke.test.mjs,
 * backend/tests/test_shared_fixtures.py and the iPad are held to, so the live
 * line under the pen is as wide as the stroke the page commits; and the cases
 * of ipad/GammaIPadTests/InkWidthTests.swift, one per law.
 */
class InkWidthTest {
    private fun sample(p: Double?, a: Double? = null) = InkWidth.Sample(0.0, 0.0, p, a)

    /** A `samples` row's stroke: `[x, y, p | null, t, a | null]`. */
    private fun stroke(rows: List<List<Double?>>): List<InkWidth.Sample> =
        rows.map { row -> InkWidth.Sample(row[0] ?: 0.0, row[1] ?: 0.0, row[2], if (row.size > 4) row[4] else null) }

    private fun pen(samples: List<InkWidth.Sample>, brush: Brush, pressure: Boolean = true, size: Double = 2.0) =
        InkWidth.widths(samples, InkKind.PEN, brush, pressure, size)

    private val comma = stroke((0..8).map { i -> listOf(i * 0.25, 0.0, 0.5, i * 8.0, null) })

    @Test
    fun everyRowOfTheSharedFixture() {
        val rows = SharedFixture.rows(SharedFixture.load("inkwidth")["rows"])
        assertFalse(rows.isEmpty())
        for (row in rows) {
            val note = row["note"] as String
            val kind = when (row["tool"]) {
                "pen" -> InkKind.PEN
                "highlighter" -> InkKind.HIGHLIGHTER
                else -> error("$note: no tool ${row["tool"]}")
            }
            val brush = Brush.named(row["brush"] as String? ?: "pen") ?: error("$note: no brush ${row["brush"]}")
            val pressure = row["pen"] as Boolean
            val size = SharedFixture.num(row["size"])
            val shape = row["shape"] != null
            val nib = (row["nib"] as Number?)?.toDouble() ?: 45.0
            val samples = row["samples"]?.let { rowsOf ->
                stroke(SharedFixture.list(rowsOf).map { s -> SharedFixture.list(s).map { (it as Number?)?.toDouble() } })
            } ?: listOf(sample((row["p"] as Number?)?.toDouble(), (row["a"] as Number?)?.toDouble()))
            val ws = InkWidth.widths(samples, kind, brush, pressure, size, shape = shape, nib = nib)
            val os = InkWidth.opacities(samples, kind, brush, pressure, shape = shape)
            fun doubles(key: String) = row[key]?.let { v -> SharedFixture.list(v).map(SharedFixture::num).toDoubleArray() }
            fun double(key: String) = (row[key] as Number?)?.toDouble()
            if (row["samples"] != null) {
                assertNear(doubles("ws"), ws, note)
                assertNear(doubles("os"), os, note)
                val at = (row["at"] as Number?)?.toInt()
                val w = double("w")
                if (at != null && w != null && ws != null && at in ws.indices) assertEquals("$note: the sample at $at", w, ws[at], 0.0005)
                doubles("p_sim")?.let { assertNear(it, InkWidth.simulatedPressure(samples, size), "$note: the speed law") }
            } else {
                assertNear(double("w")?.let { doubleArrayOf(it) }, ws, note)
                // only a Pencil writes o; every Pencil row says what it is
                assertNear(if (brush == Brush.PENCIL) double("o")?.let { doubleArrayOf(it) } else null, os, note)
            }
        }
    }

    @Test
    fun hostSamplesReadTheSameAsTheirLaws() {
        // the overloads the live line calls, on StrokeRecorder's arrays
        val host = (0..8).map { i -> doubleArrayOf(i * 0.25, 0.0, 0.5, i * 8.0) }
        assertNear(pen(comma, Brush.BRUSH), InkWidth.widths(host, InkKind.PEN, Brush.BRUSH, true, 2.0), "the comma")
        val tilted = listOf(doubleArrayOf(0.0, 0.0, 0.5, 0.0, 30.0, 120.0))
        assertNear(doubleArrayOf(2.333), InkWidth.widths(tilted, InkKind.PEN, Brush.PENCIL, true, 2.0), "the altitude is the fifth value")
        assertNear(doubleArrayOf(0.575), InkWidth.opacities(tilted, InkKind.PEN, Brush.PENCIL, true), "a Pencil's shade")
        val up = (0..5).map { i -> doubleArrayOf(10.0 + i, 10.0 - i, 0.5, i * 8.0) }
        assertNear(DoubleArray(6) { 1.0 }, InkWidth.widths(up, InkKind.PEN, Brush.CALLIGRAPHY, true, 2.0, nib = 135), "the nib as an Int")
        assertNear(
            InkWidth.simulatedPressure(comma, 2.0), InkWidth.simulatedPressure(host, 2.0), "the speed law",
        )
    }

    @Test
    fun thePensLaw() {
        assertEquals("at rest pressure, the nominal size", 1.0, InkWidth.law(Brush.PEN, sample(0.5))!!, 0.0)
        assertEquals(0.5, InkWidth.law(Brush.PEN, sample(0.0))!!, 0.0)
        assertEquals(1.5, InkWidth.law(Brush.PEN, sample(1.0))!!, 0.0)
        assertEquals("no pressure reads as 0.5", 1.0, InkWidth.law(Brush.PEN, sample(null))!!, 0.0)
        assertArrayEquals("three decimals", doubleArrayOf(1.231), pen(listOf(sample(0.731)), Brush.PEN, size = 3.0), 0.0)
        assertArrayEquals(doubleArrayOf(0.623), pen(listOf(sample(0.123)), Brush.PEN, size = 1.6), 0.0)
        assertArrayEquals("the Pen ignores the tilt", doubleArrayOf(1.3), pen(listOf(sample(0.8, a = 20.0)), Brush.PEN), 0.0)
    }

    @Test
    fun whatHasNoWidths() {
        assertNull("the Ballpoint", pen(listOf(sample(0.9)), Brush.MONOLINE, size = 3.0))
        assertNull(InkWidth.widths(listOf(sample(0.9)), InkKind.HIGHLIGHTER, Brush.PEN, true, 14.0))
        assertNull("a stroke without pressure", pen(listOf(sample(0.9, a = 60.0)), Brush.PEN, pressure = false))
        assertNull(pen(listOf(sample(null)), Brush.PENCIL, pressure = false))
        assertNull(InkWidth.opacities(listOf(sample(null)), InkKind.PEN, Brush.PENCIL, false))
        val line = stroke(listOf(listOf(0.0, 0.0, null, 0.0, null), listOf(50.0, 0.0, null, 144.0, null)))
        assertNull("a held shape is even, whatever its brush", InkWidth.widths(line, InkKind.PEN, Brush.BRUSH, false, 2.0, shape = true))
        assertNull(pen(emptyList(), Brush.PEN))
    }

    @Test
    fun theBrushsTapersAndRange() {
        assertArrayEquals("a tap has no length to taper", doubleArrayOf(1.0), pen(listOf(sample(0.5)), Brush.BRUSH), 0.0)
        // a 2 pt comma, each taper held to half its length
        assertNear(doubleArrayOf(0.1, 0.383, 0.707, 0.924, 1.0, 0.924, 0.707, 0.383, 0.1), pen(comma, Brush.BRUSH), "a short mark")
        // a 20 pt line with pressure rising 0.2 to 1
        val rising = stroke((0..20).map { i -> listOf(i.toDouble(), 0.0, 0.2 + 0.04 * i, i * 8.0, null) })
        assertNear(
            doubleArrayOf(
                0.1, 0.184, 0.396, 0.591, 0.72, 0.8, 0.88, 0.96, 1.04, 1.12, 1.2, 1.28, 1.36, 1.44, 1.52, 1.545, 1.455,
                1.245, 0.92, 0.497, 0.1,
            ),
            pen(rising, Brush.BRUSH), "the start taper over 4 pt, the end taper over 6",
        )
    }

    @Test
    fun theBrushsSpeedLawWithoutPressure() {
        val xs = listOf(0.0, 0.5, 1.0, 1.5, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 12.5, 13.0, 13.5, 14.0)
        val mouse = stroke(xs.mapIndexed { i, x -> listOf(x, 0.0, null, i * 16.0, null) })
        assertNear(
            doubleArrayOf(0.5, 0.517, 0.533, 0.548, 0.562, 0.407, 0.295, 0.214, 0.155, 0.113, 0.156, 0.197, 0.235, 0.271),
            InkWidth.simulatedPressure(mouse, 2.0), "slow is wide, fast is thin",
        )
        assertNear(
            doubleArrayOf(0.1, 0.202, 0.408, 0.609, 0.795, 0.815, 0.591, 0.428, 0.269, 0.113, 0.12, 0.102, 0.1, 0.1),
            pen(mouse, Brush.BRUSH, pressure = false), "the speed law under the tapers",
        )
    }

    @Test
    fun theLiveLineHasOnlyTheStartTaper() {
        val live = InkWidth.widths(comma, InkKind.PEN, Brush.BRUSH, true, 2.0, live = true)
        // the start taper at its full 4 pt, whatever the length so far, and no end taper
        assertNear(doubleArrayOf(0.1, 0.1, 0.195, 0.29, 0.383, 0.471, 0.556, 0.634, 0.707), live, "the stroke as it is written")
    }

    @Test
    fun thePencilsTiltAndShade() {
        assertArrayEquals(doubleArrayOf(1.0), pen(listOf(sample(0.5, a = 90.0)), Brush.PENCIL, size = 1.6), 0.0)
        assertArrayEquals(doubleArrayOf(1.667), pen(listOf(sample(0.5, a = 60.0)), Brush.PENCIL), 0.0)
        assertArrayEquals(doubleArrayOf(2.333), pen(listOf(sample(0.5, a = 30.0)), Brush.PENCIL), 0.0)
        assertArrayEquals("the widest: 1.5 × 2.333", doubleArrayOf(3.5), pen(listOf(sample(1.0, a = 30.0)), Brush.PENCIL), 0.0)
        assertArrayEquals(doubleArrayOf(1.4), pen(listOf(sample(0.2, a = 45.0)), Brush.PENCIL), 0.0)
        assertArrayEquals("no tilt reported: upright", doubleArrayOf(1.2), pen(listOf(sample(0.7)), Brush.PENCIL), 0.0)
        fun shade(s: InkWidth.Sample, brush: Brush = Brush.PENCIL) = InkWidth.opacities(listOf(s), InkKind.PEN, brush, true)
        assertArrayEquals(doubleArrayOf(0.575), shade(sample(0.5, a = 90.0)), 0.0)
        assertArrayEquals(doubleArrayOf(0.15), shade(sample(0.0)), 0.0)
        assertArrayEquals(doubleArrayOf(0.32), shade(sample(0.2, a = 45.0)), 0.0)
        assertArrayEquals(doubleArrayOf(1.0), shade(sample(1.0)), 0.0)
        assertNull("only the Pencil shades", shade(sample(0.5), Brush.PEN))
    }

    @Test
    fun theShadeBands() {
        assertEquals(0.33, InkWidth.opacityBand(0.15), 0.0)
        assertEquals(0.33, InkWidth.opacityBand(0.33), 0.0)
        assertEquals(0.66, InkWidth.opacityBand(0.575), 0.0)
        assertEquals(1.0, InkWidth.opacityBand(0.661), 0.0)
        assertEquals(1.0, InkWidth.opacityBand(1.0), 0.0)
    }

    @Test
    fun theCalligraphyPensNib() {
        // a 45° nib rises to the right: down to the right is across it, up to the right along it
        val down = stroke((0..5).map { i -> listOf(10.0 + i, 10.0 + i, 0.5, i * 8.0, null) })
        val up = stroke((0..5).map { i -> listOf(10.0 + i, 10.0 - i, 0.5, i * 8.0, null) })
        fun calligraphy(samples: List<InkWidth.Sample>, nib: Double, pressure: Boolean = true, shape: Boolean = false) =
            InkWidth.widths(samples, InkKind.PEN, Brush.CALLIGRAPHY, pressure, 2.0, shape = shape, nib = nib)
        assertNear(DoubleArray(6) { 1.0 }, calligraphy(down, 45.0), "across the nib: its whole length")
        assertNear(DoubleArray(6) { 0.12 }, calligraphy(up, 45.0), "along it: the hairline")
        assertNear(DoubleArray(6) { 1.0 }, calligraphy(up, 135.0), "a 135° nib turns it round")
        assertArrayEquals("a tap: the whole nib, wider at full pressure", doubleArrayOf(1.15), pen(listOf(sample(1.0)), Brush.CALLIGRAPHY), 0.0)
        val mouse = stroke(listOf(
            listOf(0.0, 0.0, null, 0.0, null), listOf(2.0, 2.0, null, 16.0, null), listOf(4.0, 4.0, null, 32.0, null),
            listOf(6.0, 2.0, null, 48.0, null), listOf(8.0, 0.0, null, 64.0, null),
        ))
        assertNear(doubleArrayOf(1.0, 1.0, 0.707, 0.12, 0.12), calligraphy(mouse, 45.0, pressure = false), "without pressure: the direction alone")
        assertNull("a shape is even", calligraphy(mouse, 45.0, pressure = false, shape = true))
        assertNull("no shades", InkWidth.opacities(down, InkKind.PEN, Brush.CALLIGRAPHY, true))
        val back = stroke(listOf(
            listOf(0.0, 0.0, 0.5, 0.0, null), listOf(2.0, 0.0, 0.5, 8.0, null),
            listOf(4.0, 0.0, 0.5, 16.0, null), listOf(2.0, 0.0, 0.5, 24.0, null),
        ))
        assertEquals("a reversal takes the segment ending at it", 2.0, InkWidth.travel(back, 2)!!.dx, 0.0)
        assertNull("a tap has no travel", InkWidth.travel(stroke(listOf(listOf(3.0, 3.0, 0.5, 0.0, null))), 0))
        assertNull(InkWidth.travel(back, 4))
    }

    @Test
    fun aHostSampleAsTheLawsReadIt() {
        assertEquals(InkWidth.Sample(10.0, 20.0, 0.4, 55.0), InkWidth.sample(doubleArrayOf(10.0, 20.0, 0.4, 8.0, 55.0, 120.0)))
        assertEquals(
            "four values: no tilt", InkWidth.Sample(10.0, 20.0, 0.4, null),
            InkWidth.sample(doubleArrayOf(10.0, 20.0, 0.4, 8.0)),
        )
        assertEquals(InkWidth.Sample(10.0, 20.0, null, null), InkWidth.sample(doubleArrayOf(10.0, 20.0)))
    }

    @Test
    fun roundingIsMathRounds() {
        // halves up, also below zero, as the web's Math.round
        assertEquals(1.063, InkWidth.round3(1.0625), 0.0)
        assertEquals(-1.062, InkWidth.round3(-1.0625), 0.0)
        assertEquals(0.0, InkWidth.round3(-0.0004), 0.0)
    }
}
