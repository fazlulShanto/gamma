package com.gammapdf.gamma.pen

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The ruler's edge a claimed stroke runs along: the claim's `snap`
 * (docs/dev/ipad.md "The host contract"), the web's ink/ruler.js `onRail` and
 * `railLine`, ported from ipad/GammaIPad/Pencil/RulerSnap.swift. Every point is
 * projected onto the line through (x0, y0) along (cos angle, −sin angle), the
 * angle in degrees counterclockwise as seen, in viewport CSS px with y running
 * down, and the live line is the one between the two outermost projections,
 * from the end nearer the first point's, as the page commits it. Points are
 * `[x, y, …]` (samples).
 */
object RulerSnap {
    /** One point projected onto the edge's line (ruler.js onRail), `[x, y]`. */
    fun point(x: Double, y: Double, snap: Snap): DoubleArray = at(along(x, y, snap), snap)

    /**
     * The line `points` draw along the edge: its two ends, the outermost
     * projections, the one nearer the first point's first (one point gives
     * both ends the same, a dot); none for no points.
     */
    fun project(points: List<DoubleArray>, snap: Snap): List<DoubleArray> {
        if (points.isEmpty()) return emptyList()
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (p in points) {
            val t = along(p, snap)
            lo = min(lo, t)
            hi = max(hi, t)
        }
        val t0 = along(points[0], snap)
        val (a, b) = if (t0 - lo <= hi - t0) lo to hi else hi to lo
        return listOf(at(a, snap), at(b, snap))
    }

    private fun along(p: DoubleArray, snap: Snap): Double =
        along(p.getOrElse(0) { 0.0 }, p.getOrElse(1) { 0.0 }, snap)

    /** How far along the edge a point lies, from (x0, y0). */
    private fun along(x: Double, y: Double, snap: Snap): Double {
        val a = snap.angle * Math.PI / 180
        return (x - snap.x0) * cos(a) + (y - snap.y0) * -sin(a)
    }

    private fun at(t: Double, snap: Snap): DoubleArray {
        val a = snap.angle * Math.PI / 180
        return doubleArrayOf(snap.x0 + t * cos(a), snap.y0 + t * -sin(a))
    }
}
