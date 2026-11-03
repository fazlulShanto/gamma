package com.gammapdf.gamma.pen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The lazy brush (LazyBrush), the cases of ipad/GammaIPadTests/PencilTests.swift LazyBrushTests. */
class LazyBrushTest {
    private val line: List<DoubleArray> = (0..12).map { i -> doubleArrayOf(i.toDouble(), 10.0, 0.1 * (i % 10), i * 4.0) }

    @Test
    fun noSmoothingGivesTheSamplesBack() {
        assertSamples(line, LazyBrush.smooth(line, 0))
        assertSamples(line, LazyBrush.smooth(line, 9), "a level there is no string for")
        assertSamples(line, LazyBrush.smooth(line, -1))
        assertSamples(emptyList(), LazyBrush.smooth(emptyList(), 2))
    }

    @Test
    fun theBrushTrailsThePenByItsString() {
        assertArrayEquals(doubleArrayOf(0.0, 3.0, 6.0, 10.0, 16.0), LazyBrush.radii, 0.0)
        val out = LazyBrush.smooth(line, 1) // a 3 CSS px string
        // the first where it is; x = 1, 2, 3 are within the string; then 3 short of each
        assertEquals(10, out.size)
        assertArrayEquals(line[0], out[0], 0.0)
        for ((k, sample) in out.drop(1).withIndex()) {
            val source = line[k + 4]
            assertEquals(source[0] - 3, sample[0], 1e-9)
            assertEquals(10.0, sample[1], 1e-9)
            assertArrayEquals("pressure and time ride along", source.copyOfRange(2, source.size), sample.copyOfRange(2, sample.size), 0.0)
        }
        assertEquals("the samples handed back are not changed", 4.0, line[4][0], 0.0)
    }

    @Test
    fun aShakeWithinTheStringDrawsNothing() {
        val shake = listOf(
            doubleArrayOf(0.0, 0.0, 0.5, 0.0), doubleArrayOf(2.0, 1.0, 0.5, 4.0),
            doubleArrayOf(-1.0, 2.0, 0.5, 8.0), doubleArrayOf(1.0, -2.0, 0.5, 12.0),
        )
        assertSamples(listOf(doubleArrayOf(0.0, 0.0, 0.5, 0.0)), LazyBrush.smooth(shake, 2))
    }

    @Test
    fun theRadiiCannotBeChangedFromOutside() {
        LazyBrush.radii[1] = 99.0
        assertEquals(3.0, LazyBrush.radii[1], 0.0)
    }
}
