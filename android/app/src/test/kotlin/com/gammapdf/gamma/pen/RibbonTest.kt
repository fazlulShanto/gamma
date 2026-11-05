package com.gammapdf.gamma.pen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ribbon's geometry (Ribbon) under the non-zero rule, the cases of
 * ipad/GammaIPadTests/PencilTests.swift RibbonTests, and its thinning
 * (LiveStrokeView.swift thinned).
 */
class RibbonTest {
    private fun p(x: Double, y: Double) = doubleArrayOf(x, y)

    @Test
    fun onePointIsADisc() {
        val pieces = Ribbon.pieces(listOf(p(10.0, 20.0)), doubleArrayOf(2.0))
        assertArrayEquals(doubleArrayOf(8.0, 18.0, 12.0, 22.0), Ribbon.bounds(pieces), 1e-6)
        assertTrue(Ribbon.contains(pieces, 11.0, 21.0))
        assertFalse(Ribbon.contains(pieces, 11.8, 21.8))
    }

    @Test
    fun theWidthChangesAlongIt() {
        val pieces = Ribbon.pieces(listOf(p(0.0, 0.0), p(10.0, 0.0)), doubleArrayOf(1.0, 3.0))
        assertTrue("between the two: 2 wide either side at the middle", Ribbon.contains(pieces, 5.0, 1.5))
        assertFalse(Ribbon.contains(pieces, 5.0, 2.5))
        assertTrue("the end's disc", Ribbon.contains(pieces, 12.5, 0.0))
        assertFalse(Ribbon.contains(pieces, -1.5, 0.0))
    }

    @Test
    fun overlapsAddUpUnderTheNonZeroRule() {
        // Where a disc and a quadrilateral overlap, both wound one way, the
        // point is filled; wound against each other, it would be a hole.
        val segments = listOf(
            p(0.0, 0.0) to p(10.0, 0.0), p(10.0, 0.0) to p(0.0, 0.0),
            p(0.0, 0.0) to p(0.0, 10.0), p(3.0, 7.0) to p(-4.0, -2.0),
        )
        for ((a, b) in segments) {
            val pieces = Ribbon.pieces(listOf(a, b), doubleArrayOf(2.0, 2.0))
            val middle = p((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            val near = p(a[0] + (b[0] - a[0]) * 0.05, a[1] + (b[1] - a[1]) * 0.05)
            for (point in listOf(a, b, middle, near)) {
                assertTrue("${point.toList()} on ${a.toList()} → ${b.toList()}", Ribbon.contains(pieces, point[0], point[1]))
            }
            // every piece is wound the same way: inside a disc and the quad, the winding is 2
            assertEquals(2, Ribbon.winding(pieces, near[0], near[1]))
            assertEquals(1, Ribbon.winding(pieces, middle[0], middle[1]))
        }
    }

    @Test
    fun repeatedPointsAndNoPoints() {
        assertTrue(Ribbon.pieces(emptyList(), DoubleArray(0)).isEmpty())
        assertNull(Ribbon.bounds(emptyList()))
        val pieces = Ribbon.pieces(listOf(p(5.0, 5.0), p(5.0, 5.0)), doubleArrayOf(1.0, 1.0))
        assertTrue(Ribbon.contains(pieces, 5.0, 5.5))
        assertEquals("two discs and no quad for a segment of no length", 2, pieces.size)
    }

    @Test
    fun aRadiusOfNothingDrawsNoDisc() {
        val pieces = Ribbon.pieces(listOf(p(0.0, 0.0), p(10.0, 0.0)), doubleArrayOf(0.0, 2.0))
        assertEquals(listOf(Ribbon.Piece.Disc(10.0, 0.0, 2.0)), pieces.filterIsInstance<Ribbon.Piece.Disc>())
        assertEquals(
            Ribbon.Piece.Quad(doubleArrayOf(0.0, 0.0, 10.0, -2.0, 10.0, 2.0, 0.0, 0.0)),
            pieces.filterIsInstance<Ribbon.Piece.Quad>().single(),
        )
        assertTrue("the taper's middle", Ribbon.contains(pieces, 5.0, 0.9))
        assertFalse(Ribbon.contains(pieces, 5.0, 1.1))
    }

    @Test
    fun thinningKeepsTheEndsAndDropsWhatIsCloserThanHalfAPx() {
        val points = listOf(p(0.0, 0.0), p(0.3, 0.0), p(0.6, 0.0), p(0.7, 0.0), p(1.0, 0.0), p(1.1, 0.0))
        val (kept, radii) = Ribbon.thin(points, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
        assertSamples(listOf(p(0.0, 0.0), p(0.6, 0.0), p(1.1, 0.0)), kept)
        assertArrayEquals(doubleArrayOf(1.0, 3.0, 6.0), radii, 0.0)
        val (two, twoRadii) = Ribbon.thin(listOf(p(0.0, 0.0), p(0.1, 0.0), p(9.0, 9.0)), doubleArrayOf(1.0, 2.0))
        assertSamples(listOf(p(0.0, 0.0), p(0.1, 0.0)), two, "up to two points as they are, as many as there are radii")
        assertArrayEquals(doubleArrayOf(1.0, 2.0), twoRadii, 0.0)
    }
}
