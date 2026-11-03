package com.gammapdf.gamma.pen

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A line whose width changes along it, as filled geometry, ported from
 * ipad/GammaIPad/Pencil/LiveStrokeView.swift ribbon: a disc of each point's
 * radius, and between each two points the quadrilateral their discs' sides
 * span. Every piece is wound the same way, clockwise as seen with y down, so
 * under the non-zero rule the overlaps add up and the union is filled once, at
 * the line's one colour: on a Canvas, a Path with FillType.WINDING, each disc
 * `addCircle(x, y, r, Path.Direction.CW)` and each quad its four points in
 * order. A single point is a disc. Points are `[x, y, …]`, radii CSS px.
 */
object Ribbon {
    sealed interface Piece {
        data class Disc(val x: Double, val y: Double, val r: Double) : Piece

        /** Four corners `[x0, y0, … x3, y3]`: a's right, b's right, b's left, a's left. */
        data class Quad(val pts: DoubleArray) : Piece {
            override fun equals(other: Any?): Boolean = other is Quad && pts.contentEquals(other.pts)
            override fun hashCode(): Int = pts.contentHashCode()
        }
    }

    /** The discs, each point's with a radius above 0, then a quad for each segment of some length. */
    fun pieces(points: List<DoubleArray>, radii: DoubleArray): List<Piece> {
        val n = min(points.size, radii.size)
        val out = ArrayList<Piece>(2 * n)
        for (i in 0 until n) if (radii[i] > 0) out.add(Piece.Disc(points[i][0], points[i][1], radii[i]))
        for (i in 1 until n) {
            val ax = points[i - 1][0]
            val ay = points[i - 1][1]
            val bx = points[i][0]
            val by = points[i][1]
            val dx = bx - ax
            val dy = by - ay
            val length = sqrt(dx * dx + dy * dy)
            if (!(length > 0)) continue
            // the unit normal to the left of the direction of travel, in y-up terms
            val nx = -dy / length
            val ny = dx / length
            val ra = max(radii[i - 1], 0.0)
            val rb = max(radii[i], 0.0)
            out.add(Piece.Quad(doubleArrayOf(
                ax - nx * ra, ay - ny * ra,
                bx - nx * rb, by - ny * rb,
                bx + nx * rb, by + ny * rb,
                ax + nx * ra, ay + ny * ra,
            )))
        }
        return out
    }

    /**
     * The winding number of (x, y) over the pieces: each disc containing it
     * counts 1, as a clockwise-as-seen loop does, and each quad by its edges.
     */
    fun winding(pieces: List<Piece>, x: Double, y: Double): Int = pieces.sumOf { piece ->
        when (piece) {
            is Piece.Disc -> {
                val dx = x - piece.x
                val dy = y - piece.y
                if (dx * dx + dy * dy < piece.r * piece.r) 1 else 0
            }
            is Piece.Quad -> {
                var w = 0
                val p = piece.pts
                for (k in 0 until 4) {
                    val x1 = p[2 * k]
                    val y1 = p[2 * k + 1]
                    val x2 = p[(2 * k + 2) % 8]
                    val y2 = p[(2 * k + 3) % 8]
                    val side = (x2 - x1) * (y - y1) - (x - x1) * (y2 - y1)
                    if (y1 <= y) {
                        if (y2 > y && side > 0) w++
                    } else if (y2 <= y && side < 0) {
                        w--
                    }
                }
                w
            }
        }
    }

    /** Whether the filled ribbon covers (x, y), under the non-zero rule. */
    fun contains(pieces: List<Piece>, x: Double, y: Double): Boolean = winding(pieces, x, y) != 0

    /** The pieces' bounds, `[minX, minY, maxX, maxY]`, or null for none. */
    fun bounds(pieces: List<Piece>): DoubleArray? {
        if (pieces.isEmpty()) return null
        val box = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        fun grow(x: Double, y: Double) {
            box[0] = min(box[0], x)
            box[1] = min(box[1], y)
            box[2] = max(box[2], x)
            box[3] = max(box[3], y)
        }
        for (piece in pieces) {
            when (piece) {
                is Piece.Disc -> {
                    grow(piece.x - piece.r, piece.y - piece.r)
                    grow(piece.x + piece.r, piece.y + piece.r)
                }
                is Piece.Quad -> for (k in 0 until 4) grow(piece.pts[2 * k], piece.pts[2 * k + 1])
            }
        }
        return box
    }

    /**
     * The ribbon's points without those closer than half a CSS px to the last
     * one kept (the first and the last always kept), with their radii: a fast
     * digitizer's trace is dense where the pen is slow, and each point is a
     * disc to fill. Up to two points come back as they are.
     */
    fun thin(points: List<DoubleArray>, radii: DoubleArray): Pair<List<DoubleArray>, DoubleArray> {
        val n = min(points.size, radii.size)
        if (n <= 2) return points.take(n) to radii.copyOf(n)
        val kept = ArrayList<DoubleArray>(n)
        val keptRadii = ArrayList<Double>(n)
        kept.add(points[0])
        keptRadii.add(radii[0])
        for (i in 1 until n) {
            val p = points[i]
            val q = kept[kept.size - 1]
            val dx = p[0] - q[0]
            val dy = p[1] - q[1]
            if (dx * dx + dy * dy >= 0.25 || i == n - 1) {
                kept.add(p)
                keptRadii.add(radii[i])
            }
        }
        return kept to keptRadii.toDoubleArray()
    }
}
