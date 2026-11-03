package com.gammapdf.gamma.pen

import com.gammapdf.gamma.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ruler's edge (the claim's `snap`): the web's ink/ruler.js onRail and
 * railLine, the cases of ipad/GammaIPadTests/PencilTests.swift RulerSnapTests.
 * The line's wobbly and right-to-left strokes are railLine's cases in
 * frontend/tests/ruler.test.mjs, in view coordinates. That HostInfo declares
 * the `snap` look is Bridge's (shell/BridgeTest).
 */
class RulerSnapTest {
    private fun p(x: Double, y: Double) = doubleArrayOf(x, y)

    @Test
    fun aPointIsProjectedOntoTheEdge() {
        val level = Snap(100.0, 200.0, 0.0)
        assertArrayEquals(p(130.0, 200.0), RulerSnap.point(130.0, 190.0, level), 0.0)
        // 90°: the edge runs up the screen (counterclockwise as seen, y down)
        assertArrayEquals(p(50.0, 20.0), RulerSnap.point(80.0, 20.0, Snap(50.0, 50.0, 90.0)), 1e-9)
        // 30°: along (cos 30°, −sin 30°) from the origin, 100 cos 30° out
        val tilted = RulerSnap.point(100.0, 0.0, Snap(0.0, 0.0, 30.0))
        assertEquals(75.0, tilted[0], 1e-9)
        assertEquals(-43.30127, tilted[1], 1e-5)
    }

    @Test
    fun theLineRunsBetweenTheOutermostProjectionsFromTheEndNearerTheFirst() {
        val level = Snap(100.0, 200.0, 0.0)
        val wobbly = listOf(p(150.0, 195.0), p(260.0, 205.0), p(330.0, 190.0), p(290.0, 199.0))
        assertSamples(listOf(p(150.0, 200.0), p(330.0, 200.0)), RulerSnap.project(wobbly, level), "it went back, but the line reaches the farthest point")
        assertSamples(
            listOf(p(450.0, 200.0), p(160.0, 200.0)),
            RulerSnap.project(listOf(p(450.0, 190.0), p(300.0, 185.0), p(160.0, 205.0)), level),
            "drawn right to left, it starts at the right",
        )
        assertSamples(listOf(p(120.0, 200.0), p(120.0, 200.0)), RulerSnap.project(listOf(p(120.0, 190.0)), level), "a dot of the edge")
        assertSamples(emptyList(), RulerSnap.project(emptyList(), level))
        // samples carry more than x and y
        assertSamples(
            listOf(p(150.0, 200.0), p(160.0, 200.0)),
            RulerSnap.project(listOf(doubleArrayOf(150.0, 195.0, 0.5, 0.0), doubleArrayOf(160.0, 205.0, 0.5, 8.0, 60.0, 90.0)), level),
        )
    }

    @Test
    fun theEdgeTheClaimCarriesIsRead() {
        @Suppress("UNCHECKED_CAST")
        val claim = Json.parse("""{"kind":"pen","color":"#222222","size":2,"snap":{"x0":10.5,"y0":20,"angle":30}}""") as Map<String, Any?>
        assertEquals(Snap(10.5, 20.0, 30.0), InkTool.decode(claim).snap)
    }
}
