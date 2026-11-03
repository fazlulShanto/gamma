package com.gammapdf.gamma.pen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A shape the page snapped a held stroke to: its answer to `hold`
 * (HoldReply), in the parameters of the web's frontend/src/ink/shapes.js, in
 * viewport CSS px (`angle` in degrees, `n` a count). Ported from
 * ipad/GammaIPad/Pencil/HeldShape.swift. The live line draws it (`outline`)
 * and refits it as the pen moves on (`refit`, ShapeHold), by the rules the
 * page runs again on the final samples, so the shape under the pen is the one
 * that commits. Pure, and held to tests/shared/inkhold.json, as shapes.js is.
 *
 * Params by kind: line, arrow `{x1, y1, x2, y2}` (an arrow's tip at x2, y2,
 * with an optional `head`, its barbs' length); triangle `{x1, y1, x2, y2, x3,
 * y3}`; rect `{cx, cy, w, h, angle?}`; polygon `{cx, cy, r, n, angle?}`; circle
 * `{cx, cy, r}`; ellipse `{cx, cy, rx, ry, angle?}`.
 */
data class HeldShape(val kind: String, val params: Map<String, Double>) {
    init {
        require(kind in KINDS) { "no shape of kind $kind" }
    }

    /**
     * Whether `params` holds what the kind is drawn from, as finite numbers
     * (`angle` and an arrow's `head` may be left out, as shapes.js reads
     * them), and a polygon has at least three sides.
     */
    val drawable: Boolean
        get() {
            val needed = when (kind) {
                "line", "arrow" -> listOf("x1", "y1", "x2", "y2")
                "triangle" -> listOf("x1", "y1", "x2", "y2", "x3", "y3")
                "rect" -> listOf("cx", "cy", "w", "h")
                "polygon" -> listOf("cx", "cy", "r", "n")
                "circle" -> listOf("cx", "cy", "r")
                else -> listOf("cx", "cy", "rx", "ry")
            }
            return needed.all { params[it]?.isFinite() == true } && params.values.all { it.isFinite() } &&
                (kind != "polygon" || value("n") >= 3)
        }

    /** The outline comes back to its first point: every kind but a line and an arrow. */
    val closed: Boolean get() = kind != "line" && kind != "arrow"

    /**
     * The shape as the live line strokes it, `[x, y]` points (shapes.js
     * shapeSamples' corners): a line's two ends; an arrow's tail, tip, barb,
     * tip, barb; a triangle's, a rectangle's or a polygon's corners; a circle
     * or an ellipse as a polygon within 0.1 CSS px of it, its count a multiple
     * of four from angle 0, so the axes' ends are corners. A closed outline
     * (`closed`) does not repeat its first point.
     */
    fun outline(): List<DoubleArray> = when (kind) {
        "line" -> listOf(point("x1", "y1"), point("x2", "y2"))
        "arrow" -> arrowPoints()
        "triangle" -> listOf(point("x1", "y1"), point("x2", "y2"), point("x3", "y3"))
        "rect", "polygon" -> corners()
        "circle" -> ellipsePoints(value("r"), value("r"), 0.0)
        else -> ellipsePoints(value("rx"), value("ry"), value("angle"))
    }

    /** An arrow's polyline (shapes.js arrowPolyline): the barbs at 30° to the shaft. */
    private fun arrowPoints(): List<DoubleArray> {
        val tail = point("x1", "y1")
        val tip = point("x2", "y2")
        val along = hypot(tail[0] - tip[0], tail[1] - tip[1])
        val length = if (along > 0) along else 1.0
        val head = params["head"] ?: min(length / 4, 24.0)
        // back along the shaft, from the tip
        val bx = (tail[0] - tip[0]) / length
        val by = (tail[1] - tip[1]) / length
        val barbs = listOf(1.0, -1.0).map { sign ->
            val a = sign * 30 / DEGREES
            doubleArrayOf(tip[0] + head * (bx * cos(a) - by * sin(a)), tip[1] + head * (bx * sin(a) + by * cos(a)))
        }
        return listOf(tail, tip, barbs[0], tip.copyOf(), barbs[1])
    }

    /** A rectangle's or a polygon's corners in order (shapes.js shapeCorners). */
    private fun corners(): List<DoubleArray> {
        val cx = value("cx")
        val cy = value("cy")
        val angle = value("angle")
        if (kind == "rect") {
            val turn = angle / DEGREES
            val c = cos(turn)
            val s = sin(turn)
            val hw = value("w") / 2
            val hh = value("h") / 2
            return listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { (u, v) ->
                doubleArrayOf(cx + u * c - v * s, cy + u * s + v * c)
            }
        }
        val sides = jsRound(value("n"))
        val n = if (sides.isFinite()) min(max(sides, 3.0), 64.0).toInt() else 3
        val r = value("r")
        return List(n) { i ->
            val a = (angle + 360.0 / n * i) / DEGREES
            doubleArrayOf(cx + r * cos(a), cy + r * sin(a))
        }
    }

    /**
     * An ellipse of semi-axes rx, ry turned by `angle` degrees about (cx, cy),
     * as shapes.js places its samples (x = cx + u cos − v sin, y = cy + u sin +
     * v cos), with as many corners as keep each side within 0.1 CSS px of the
     * curve: at least 48 (shapes.js ELLIPSE_MIN), at most 4096.
     */
    private fun ellipsePoints(rx: Double, ry: Double, angle: Double): List<DoubleArray> {
        val cx = value("cx")
        val cy = value("cy")
        val turn = angle / DEGREES
        val c = cos(turn)
        val s = sin(turn)
        val radius = max(abs(rx), abs(ry))
        val fine = if (radius > ROUND_TOLERANCE && radius.isFinite()) ceil(PI / acos(1 - ROUND_TOLERANCE / radius)) else 0.0
        val count = (4 * ceil(min(max(fine, 48.0), 4096.0) / 4)).toInt()
        return List(count) { i ->
            val a = 2 * PI * i / count
            val u = rx * cos(a)
            val v = ry * sin(a)
            doubleArrayOf(cx + u * c - v * s, cy + u * s + v * c)
        }
    }

    /**
     * The shape as the pen has moved it on since the snap (shapes.js
     * refitShape, without Shift), computed from the snap's params every time,
     * never from an earlier refit: a line or an arrow takes the pointer
     * (px, py) as its end; a closed shape scales about its centre by |pointer −
     * centre| / |from − centre| and turns about it by the angle from `from` to
     * the pointer, a rectangle or a triangle keeping its proportions.
     * Normalised and rounded as the web's recogniser returns a shape.
     */
    fun refit(px: Double, py: Double, fromX: Double, fromY: Double): HeldShape {
        if (kind == "line" || kind == "arrow") {
            val out = linkedMapOf("x1" to value("x1"), "y1" to value("y1"), "x2" to round2(px), "y2" to round2(py))
            if (kind == "arrow") params["head"]?.let { out["head"] = it }
            return HeldShape(kind, out)
        }
        val (centreX, centreY) = centre()
        val ox = fromX - centreX
        val oy = fromY - centreY
        val reach = hypot(ox, oy)
        val scale = if (reach > 1e-6) hypot(px - centreX, py - centreY) / reach else 1.0
        val deg = if (reach > 1e-6) (atan2(py - centreY, px - centreX) - atan2(oy, ox)) * DEGREES else 0.0
        val rad = deg / DEGREES
        val angle = value("angle") + deg
        return when (kind) {
            "triangle" -> {
                val out = LinkedHashMap<String, Double>()
                for ((xKey, yKey) in listOf("x1" to "y1", "x2" to "y2", "x3" to "y3")) {
                    val dx = (value(xKey) - centreX) * scale
                    val dy = (value(yKey) - centreY) * scale
                    out[xKey] = centreX + dx * cos(rad) - dy * sin(rad)
                    out[yKey] = centreY + dx * sin(rad) + dy * cos(rad)
                }
                HeldShape(kind, rounded(out))
            }
            "rect" -> HeldShape(kind, rounded(normalisedRect(value("cx"), value("cy"), value("w") * scale, value("h") * scale, angle)))
            "polygon" -> {
                val n = value("n")
                HeldShape(kind, rounded(linkedMapOf(
                    "cx" to value("cx"), "cy" to value("cy"), "r" to value("r") * scale, "n" to n,
                    "angle" to mod(round2(angle), 360 / n),
                )))
            }
            "circle" -> HeldShape(kind, rounded(linkedMapOf("cx" to value("cx"), "cy" to value("cy"), "r" to value("r") * scale)))
            else -> {
                var rx = value("rx") * scale
                var ry = value("ry") * scale
                var turned = angle
                if (rx < ry) {
                    rx = ry.also { ry = rx }
                    turned += 90
                }
                HeldShape(kind, rounded(linkedMapOf(
                    "cx" to value("cx"), "cy" to value("cy"), "rx" to rx, "ry" to ry, "angle" to mod(round2(turned), 180.0),
                )))
            }
        }
    }

    /** `{kind, params}`, the record both ways. */
    fun encode(): Map<String, Any?> = mapOf("kind" to kind, "params" to params)

    /** The point a closed shape scales and turns about: a triangle's centroid, else its centre. */
    private fun centre(): Pair<Double, Double> =
        if (kind == "triangle") {
            (value("x1") + value("x2") + value("x3")) / 3 to (value("y1") + value("y2") + value("y3")) / 3
        } else {
            value("cx") to value("cy")
        }

    private fun value(key: String): Double = params[key] ?: 0.0

    private fun point(x: String, y: String) = doubleArrayOf(value(x), value(y))

    companion object {
        /** The kinds the live line draws; the page's `curve`, and any other, is none. */
        val KINDS = setOf("line", "arrow", "triangle", "rect", "polygon", "circle", "ellipse")

        const val DEGREES = 180 / PI

        /** How far a round shape's outline may stray from the curve, CSS px. */
        private const val ROUND_TOLERANCE = 0.1

        /**
         * `{kind, params}` (a decoded JSON object), or null when the kind is
         * not one of KINDS, a param is not a number, or the params cannot draw
         * it (`drawable`).
         */
        fun decode(v: Any?): HeldShape? {
            val m = v as? Map<*, *> ?: return null
            val kind = m["kind"] as? String ?: return null
            if (kind !in KINDS) return null
            val raw = m["params"] as? Map<*, *> ?: return null
            val params = LinkedHashMap<String, Double>()
            for ((key, value) in raw) {
                if (key !is String || value !is Number) return null
                params[key] = value.toDouble()
            }
            return HeldShape(kind, params).takeIf { it.drawable }
        }

        /** Math.round: the nearest whole number, halves up. */
        fun jsRound(v: Double): Double = floor(v + 0.5)

        /** Two decimals, as shapes.js round2 (Math.round(v × 100) / 100). */
        fun round2(v: Double): Double = floor(v * 100 + 0.5) / 100

        /** Every param to two decimals but `n`, a count (shapes.js rounded). */
        private fun rounded(params: Map<String, Double>): Map<String, Double> =
            params.mapValuesTo(LinkedHashMap()) { (key, v) -> if (key == "n") v else round2(v) }

        /**
         * shapes.js normaliseRect: the angle in [−45°, 45°), the side along it
         * staying `w` (a quarter turn swaps the pair), a turn of nothing 0.
         */
        private fun normalisedRect(cx: Double, cy: Double, w: Double, h: Double, angle: Double): Map<String, Double> {
            var a = mod(round2(angle) + 45, 90.0) - 45
            val quarter = jsRound((angle - a) / 90)
            var width = w
            var height = h
            if (quarter % 2 != 0.0) width = height.also { height = width }
            if (abs(a) < 1e-9) a = 0.0
            return linkedMapOf("cx" to cx, "cy" to cy, "w" to width, "h" to height, "angle" to a)
        }
    }
}

/**
 * A held stroke after the snap (docs/dev/ipad.md "The pen", step 6), ported
 * from ipad/GammaIPad/Pencil/HeldShape.swift ShapeHold: which shape the samples
 * come to as the pen moves on, the rule the page runs at the lift too
 * (shapes.js followHold; tests/shared/inkhold.json). `n` is the number of
 * samples the hold carried, (fromX, fromY) the last of them, where the pen
 * stood, and `tSnap` the snap's time on the samples' clock (the stillness
 * anchor's t + the hold's delay, in ms). Of the samples from index `n` on, one
 * more than CANCEL_SLOP from `from` within CANCEL_WINDOW of `tSnap` drops the
 * shape for the rest of the stroke (null: freehand comes back); once one has
 * been REFIT_SLOP from `from`, the shape is refit to the latest sample; until
 * then it is the snap.
 */
class ShapeHold(val snap: HeldShape, val fromX: Double, val fromY: Double, val n: Int, val tSnap: Double) {
    /** The shape for the stroke's samples so far (`[x, y, p, t, …]`, StrokeRecorder), or null once it was dropped. */
    fun current(samples: List<DoubleArray>): HeldShape? {
        var refitting = false
        var latest: DoubleArray? = null
        for (i in max(0, n) until samples.size) {
            val s = samples[i]
            if (s.size < 4) continue
            val moved = hypot(s[0] - fromX, s[1] - fromY)
            if (moved > CANCEL_SLOP && s[3] - tSnap <= CANCEL_WINDOW) return null
            if (moved >= REFIT_SLOP) refitting = true
            latest = s
        }
        val pointer = latest
        if (!refitting || pointer == null) return snap
        return snap.refit(pointer[0], pointer[1], fromX, fromY)
    }

    companion object {
        /** shapes.js REFIT_PX, CANCEL_PX (CSS px) and CANCEL_MS. */
        const val REFIT_SLOP = 6.0
        const val CANCEL_SLOP = 12.0
        const val CANCEL_WINDOW = 150.0
    }
}

/**
 * ink.js mod, which the web's ink modules share: a remainder in [0, m)
 * whatever the sign of v. A held shape's angles fold by it, and so do the live
 * nib (InkTool.liveNib) and a sample's roll. The second remainder matters: for
 * a v just below 0, v + m rounds to m itself, which it folds to 0.
 */
internal fun mod(v: Double, m: Double): Double = (v % m + m) % m
