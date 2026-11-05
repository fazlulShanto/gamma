package com.gammapdf.gamma.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HeldShape and ShapeHold, by hand (the expectations are shapes.js
 * refitShape's for the same input) and against every case of
 * tests/shared/inkhold.json, which shapes.test.mjs and the iPad run too: the
 * cases of ipad/GammaIPadTests/PencilTests.swift HeldShapeTests.
 */
class HeldShapeTest {
    private fun refit(kind: String, params: Map<String, Double>, from: Pair<Double, Double>, to: Pair<Double, Double>) =
        HeldShape(kind, params).refit(to.first, to.second, from.first, from.second).params

    /** An outline's bounds, `[minX, minY, maxX, maxY]`. */
    private fun box(shape: HeldShape): DoubleArray {
        val points = shape.outline()
        return doubleArrayOf(points.minOf { it[0] }, points.minOf { it[1] }, points.maxOf { it[0] }, points.maxOf { it[1] })
    }

    @Test
    fun aLineOrAnArrowTakesThePenAsItsEnd() {
        assertParams(
            mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 110.0, "y2" to 5.01),
            refit("line", mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 100.0, "y2" to 0.0), 100.0 to 0.0, 110.004 to 5.006), "a line",
        )
        assertParams(
            mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 50.0, "y2" to 50.0, "head" to 20.0),
            refit("arrow", mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 100.0, "y2" to 0.0, "head" to 20.0), 100.0 to 0.0, 50.0 to 50.0),
            "an arrow keeps its head",
        )
        assertParams(
            mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 50.0, "y2" to 50.0),
            refit("arrow", mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 100.0, "y2" to 0.0), 100.0 to 0.0, 50.0 to 50.0),
            "and gains none it did not have",
        )
    }

    @Test
    fun aClosedShapeScalesAndTurnsAboutItsCentre() {
        assertParams(
            mapOf("cx" to 50.0, "cy" to 50.0, "r" to 60.0),
            refit("circle", mapOf("cx" to 50.0, "cy" to 50.0, "r" to 50.0), 100.0 to 50.0, 110.0 to 50.0), "a circle grows",
        )
        assertParams(
            mapOf("cx" to 50.0, "cy" to 50.0, "r" to 50.0),
            refit("circle", mapOf("cx" to 50.0, "cy" to 50.0, "r" to 50.0), 50.0 to 50.0, 70.0 to 50.0),
            "from the centre itself: no scale, no turn",
        )
        val rect = mapOf("cx" to 0.0, "cy" to 0.0, "w" to 40.0, "h" to 20.0, "angle" to 0.0)
        assertParams(
            mapOf("cx" to 0.0, "cy" to 0.0, "w" to 20.0, "h" to 40.0, "angle" to 0.0),
            refit("rect", rect, 20.0 to 0.0, 0.0 to 20.0), "a quarter turn swaps the sides",
        )
        assertParams(
            mapOf("cx" to 0.0, "cy" to 0.0, "w" to 40.2, "h" to 20.1, "angle" to 5.71),
            refit("rect", rect, 20.0 to 0.0, 20.0 to 2.0), "a slight turn and growth",
        )
        assertParams(
            mapOf("cx" to 10.0, "cy" to 10.0, "w" to 30.0, "h" to 60.0, "angle" to 30.0),
            refit("rect", mapOf("cx" to 10.0, "cy" to 10.0, "w" to 40.0, "h" to 20.0, "angle" to 30.0), 30.0 to 10.0, 10.0 to 40.0),
            "turned past 45°",
        )
        assertParams(
            mapOf("cx" to 0.0, "cy" to 0.0, "rx" to 30.0, "ry" to 10.0, "angle" to 80.0),
            refit("ellipse", mapOf("cx" to 0.0, "cy" to 0.0, "rx" to 30.0, "ry" to 10.0, "angle" to 170.0), 30.0 to 0.0, 0.0 to 30.0),
            "the angle in [0°, 180°)",
        )
        assertParams(
            mapOf("cx" to 0.0, "cy" to 0.0, "rx" to 120.0, "ry" to 40.0, "angle" to 0.0),
            refit("ellipse", mapOf("cx" to 0.0, "cy" to 0.0, "rx" to 30.0, "ry" to 10.0, "angle" to 0.0), 0.0 to 10.0, 0.0 to 40.0),
            "four times as far out",
        )
        assertParams(
            mapOf("cx" to 0.0, "cy" to 0.0, "r" to 10.0, "n" to 5.0, "angle" to 64.0),
            refit("polygon", mapOf("cx" to 0.0, "cy" to 0.0, "r" to 10.0, "n" to 5.0, "angle" to 10.0), 10.0 to 0.0, 0.0 to -10.0),
            "the angle in [0, 360 / n)",
        )
        assertParams(
            mapOf("x1" to -8.0, "y1" to -4.0, "x2" to 40.0, "y2" to -10.0, "x3" to -2.0, "y3" to 44.0),
            refit("triangle", mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 30.0, "y2" to 0.0, "x3" to 0.0, "y3" to 30.0), 30.0 to 0.0, 40.0 to -10.0),
            "a triangle about its centroid",
        )
    }

    @Test
    fun theHoldFollowsThePenOnceItLeavesTheSnap() {
        val snap = HeldShape("line", mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 100.0, "y2" to 0.0))
        val hold = ShapeHold(snap, 100.0, 0.0, n = 2, tSnap = 600.0)
        val held = listOf(doubleArrayOf(0.0, 0.0, 0.5, 0.0), doubleArrayOf(100.0, 0.0, 0.5, 400.0))
        // the shape once `more` samples followed the two the hold carried
        fun current(vararg more: DoubleArray) = hold.current(held + more.toList())
        assertEquals("nothing since the snap", snap, hold.current(held))
        assertEquals("within the refit slop", snap, current(doubleArrayOf(103.0, 0.0, 0.5, 620.0), doubleArrayOf(104.0, 0.0, 0.5, 700.0)))
        assertEquals("6 or more out: refit", 110.0, current(doubleArrayOf(110.0, 0.0, 0.5, 800.0))?.params?.get("x2")!!, 0.0)
        assertEquals(
            "once out, it follows the pen back", 100.5,
            current(doubleArrayOf(110.0, 0.0, 0.5, 800.0), doubleArrayOf(100.5, 0.0, 0.5, 850.0))?.params?.get("x2")!!, 0.0,
        )
        assertNull("far and fast right after the snap: freehand", current(doubleArrayOf(113.0, 0.0, 0.5, 700.0)))
        assertEquals("as far after the window: a refit", 113.0, current(doubleArrayOf(113.0, 0.0, 0.5, 760.0))?.params?.get("x2")!!, 0.0)
        assertNull(
            "dropped for the rest of the stroke",
            current(doubleArrayOf(113.0, 0.0, 0.5, 700.0), doubleArrayOf(100.0, 0.0, 0.5, 900.0)),
        )
        val past = listOf(doubleArrayOf(150.0, 0.0, 0.5, 610.0))
        assertEquals(
            "the samples the hold carried are not followed", snap,
            ShapeHold(snap, 100.0, 0.0, n = 3, tSnap = 600.0).current(held + past),
        )
    }

    @Test
    fun aShapesOutline() {
        val circle = box(HeldShape("circle", mapOf("cx" to 50.0, "cy" to 60.0, "r" to 10.0)))
        assertEquals(40.0, circle[0], 1e-6)
        assertEquals(70.0, circle[3], 1e-6)
        val rect = box(HeldShape("rect", mapOf("cx" to 0.0, "cy" to 0.0, "w" to 40.0, "h" to 20.0, "angle" to 90.0)))
        assertEquals("turned a quarter: as wide as it was tall", 20.0, rect[2] - rect[0], 1e-6)
        assertEquals(40.0, rect[3] - rect[1], 1e-6)
        val ellipse = box(HeldShape("ellipse", mapOf("cx" to 0.0, "cy" to 0.0, "rx" to 30.0, "ry" to 10.0, "angle" to 90.0)))
        assertEquals(60.0, ellipse[3] - ellipse[1], 1e-3)
        val line = box(HeldShape("line", mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 100.0, "y2" to 50.0)))
        assertEquals(100.0, line[2] - line[0], 0.0)
        assertEquals(50.0, line[3] - line[1], 0.0)
        val arrow = box(HeldShape("arrow", mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 100.0, "y2" to 0.0, "head" to 20.0)))
        assertEquals("the barbs at 30° to the shaft", -10.0, arrow[1], 1e-6)
        assertEquals(10.0, arrow[3], 1e-6)
        assertFalse(HeldShape("polygon", mapOf("cx" to 0.0, "cy" to 0.0, "r" to 10.0, "n" to 5.0, "angle" to 0.0)).outline().isEmpty())
    }

    @Test
    fun anOutlinesPointsAndWhetherItCloses() {
        val arrow = HeldShape("arrow", mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 100.0, "y2" to 0.0)).outline()
        assertEquals("tail, tip, barb, tip, barb", 5, arrow.size)
        assertEquals("a head a quarter of the shaft, at most 24", 100 - 24 * Math.cos(Math.PI / 6), arrow[2][0], 1e-9)
        assertEquals(listOf(100.0, 0.0), arrow[3].toList())
        assertEquals(3, HeldShape("triangle", mapOf("x1" to 0.0, "y1" to 0.0, "x2" to 9.0, "y2" to 0.0, "x3" to 0.0, "y3" to 9.0)).outline().size)
        assertEquals(4, HeldShape("rect", mapOf("cx" to 0.0, "cy" to 0.0, "w" to 4.0, "h" to 2.0)).outline().size)
        val polygon = { n: Double -> HeldShape("polygon", mapOf("cx" to 0.0, "cy" to 0.0, "r" to 10.0, "n" to n)).outline().size }
        assertEquals(5, polygon(5.0))
        assertEquals("a count rounded as Math.round", 6, polygon(5.5))
        assertEquals(64, polygon(200.0))
        assertEquals(3, polygon(Double.NaN))
        val round = HeldShape("circle", mapOf("cx" to 0.0, "cy" to 0.0, "r" to 500.0)).outline()
        assertEquals("a corner count of four", 0, round.size % 4)
        assertTrue("finer for a larger circle", round.size > 48)
        for (i in round.indices) {
            val a = round[i]
            val b = round[(i + 1) % round.size]
            val mid = Math.hypot((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            assertTrue("each side within 0.1 of the curve", 500 - mid <= 0.1 + 1e-9)
        }
        assertEquals(48, HeldShape("circle", mapOf("cx" to 0.0, "cy" to 0.0, "r" to 0.0)).outline().size)
        assertEquals("a radius no screen has", 4096, HeldShape("circle", mapOf("cx" to 0.0, "cy" to 0.0, "r" to 1e300)).outline().size)
        assertFalse(HeldShape("line", mapOf()).closed)
        assertFalse(HeldShape("arrow", mapOf()).closed)
        for (kind in listOf("triangle", "rect", "polygon", "circle", "ellipse")) assertTrue(kind, HeldShape(kind, mapOf()).closed)
    }

    @Test
    fun aRecordDecodesAndEncodes() {
        val record = mapOf("kind" to "rect", "params" to mapOf("cx" to 50, "cy" to 60.5, "w" to 40, "h" to 20))
        val shape = HeldShape.decode(record)!!
        assertEquals(HeldShape("rect", mapOf("cx" to 50.0, "cy" to 60.5, "w" to 40.0, "h" to 20.0)), shape)
        assertEquals(mapOf("kind" to "rect", "params" to shape.params), shape.encode())
        assertEquals(shape, HeldShape.decode(shape.encode()))
        assertNull(HeldShape.decode(mapOf("kind" to "curve", "params" to mapOf("x1" to 0, "y1" to 0, "x2" to 1, "y2" to 1))))
        assertNull(HeldShape.decode(mapOf("params" to mapOf("cx" to 0, "cy" to 0, "r" to 4))))
        assertNull(HeldShape.decode(mapOf("kind" to "circle")))
        assertNull(HeldShape.decode(mapOf("kind" to "circle", "params" to mapOf("cx" to 0, "cy" to 0, "r" to null))))
        assertNull(HeldShape.decode(mapOf("kind" to "circle", "params" to mapOf("cx" to 0, "cy" to 0, "r" to true))))
        assertNull(HeldShape.decode(mapOf("kind" to "ellipse", "params" to mapOf("cx" to 0, "cy" to 0, "rx" to 4, "ry" to 2, "angle" to Double.NaN))))
        assertNull(HeldShape.decode("circle"))
    }

    @Test
    fun everyCaseOfTheSharedFixture() {
        val fixture = SharedFixture.load("inkhold")
        val still = SharedFixture.rows(fixture["still"])
        val follow = SharedFixture.rows(fixture["follow"])
        val refits = SharedFixture.rows(fixture["refit"])
        assertFalse(still.isEmpty())
        assertFalse(follow.isEmpty())
        assertFalse(refits.isEmpty())
        for (row in still) {
            val note = row["note"] as String
            val recorder = StrokeRecorder()
            for (sample in SharedFixture.list(row["samples"]).map(SharedFixture::hostSample)) {
                recorder.add(sample[0], sample[1], 1.0, 2.0, sample[3] / 1000)
            }
            assertEquals(note, SharedFixture.num(row["stillSince"]).toInt(), recorder.stillSince)
        }
        for (row in follow) {
            val note = row["note"] as String
            val hold = row["hold"] as Map<*, *>
            val from = SharedFixture.list(hold["from"]).map(SharedFixture::num)
            val shapeHold = ShapeHold(
                HeldShape(hold["kind"] as String, SharedFixture.params(hold["params"])!!), from[0], from[1],
                SharedFixture.num(hold["n"]).toInt(), SharedFixture.num(hold["tSnap"]),
            )
            val samples = SharedFixture.list(row["samples"]).map(SharedFixture::hostSample)
            assertParams(SharedFixture.params(row["expect"]), shapeHold.current(samples)?.params, note)
        }
        for (row in refits) {
            val note = row["note"] as String
            val from = SharedFixture.list(row["from"]).map(SharedFixture::num)
            val pointer = SharedFixture.list(row["pointer"]).map(SharedFixture::num)
            val shape = HeldShape(row["kind"] as String, SharedFixture.params(row["params"])!!)
            assertParams(SharedFixture.params(row["expect"]), shape.refit(pointer[0], pointer[1], from[0], from[1]).params, note)
        }
    }
}
