package com.gammapdf.gamma.pen

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What a captured stroke's live line is, frame by frame, in viewport CSS px
 * (docs/dev/android.md "The pen"): the geometry of
 * ipad/GammaIPad/Pencil/LiveStrokeView.swift `show`, which LiveInk draws. A
 * held shape the page snapped the stroke to is its outline (HeldShape), even
 * end to end at the tool's width, in its dash; along the ruler (the claim's
 * `snap`), the line between the outermost projections of the samples onto its
 * edge (RulerSnap); else the samples through the lazy brush when the preset
 * smooths (LazyBrush), with the predicted points after them when it does not:
 * a ribbon as wide as the brush's law makes each sample (InkWidth, live) at
 * the tool's colour and opacity, a Pencil's shaded by the band of its mean
 * opacity; a dashed line, even under pressure; or the plain polyline. The
 * saved stroke is the page's, made from the samples handed back.
 *
 * The iPad builds the whole line again at every frame. Here what can no longer
 * change is kept: the lazy brush only adds points; every point but the last
 * has its final width, worked out once (LiveWidths; only the last one's
 * calligraphy travel waits for the next); and thinning keeps or drops each
 * point by the ones before it, but for the last point, which is always kept.
 * So a frame is the `settled` part, which only grows during the stroke, and a
 * `tail` made again each time: the last point and the prediction. Together
 * they are exactly the iPad's line.
 */
class LiveLine(val tool: InkTool) {
    /** One frame's line; `settled` grows during the stroke and is read at once, before the next frame. */
    sealed interface Look {
        /** The colour, ARGB, its alpha the tool's opacity (a Pencil's shaded). */
        val argb: Int

        /** Nothing to draw. */
        val isEmpty: Boolean
    }

    /** The ribbon (Ribbon), filled under the non-zero rule: the `settled` pieces, then the `tail`'s. */
    class Fill(val settled: List<Ribbon.Piece>, val tail: List<Ribbon.Piece>, override val argb: Int) : Look {
        override val isEmpty: Boolean get() = settled.isEmpty() && tail.isEmpty()
    }

    /**
     * A polyline (`[x, y, …]` points: the `settled` ones, then the `tail`'s)
     * stroked at `width` with round caps and joins, in `dash` ([on, off])
     * from its start; a single point is a dot. `closed` joins its end to its
     * start. `outline`: it is a held shape's outline (`shape`) or the ruler's
     * line, made whole at every frame.
     */
    class Stroke(
        val settled: List<DoubleArray>,
        val tail: List<DoubleArray>,
        val width: Double,
        val dash: List<Double>?,
        val closed: Boolean,
        val outline: Boolean,
        val shape: Boolean,
        override val argb: Int,
    ) : Look {
        override val isEmpty: Boolean get() = settled.isEmpty() && tail.isEmpty()
    }

    private val width = max(tool.size, 0.5)
    private val color = CssColor.parse(tool.color) ?: INK
    private val ink = CssColor.withAlpha(color, tool.opacity)
    /** A dashed line is even, even under pressure: the committed dashes have their widths. */
    private val ribbon = tool.dash == null && InkWidth.hasWidths(tool.kind, tool.brush, tool.pressure, shape = false)
    private val widths = LiveWidths(tool)

    /** The last shape drawn and its outline. */
    private var outlined: HeldShape? = null
    private var outline: List<DoubleArray> = emptyList()

    /** The lazy brush's points so far, and how many samples it has read. */
    private val drawn = ArrayList<DoubleArray>()
    private var read = 0

    /** The ribbon's settled pieces; the last point kept of the settled ones, its radius; how many drawn points were thinned. */
    private val settled = ArrayList<Ribbon.Piece>()
    private var kept: DoubleArray? = null
    private var keptRadius = 0.0
    private var thinned = 0

    /**
     * The line for the stroke's samples so far (StrokeRecorder), the predicted
     * points after them (`[x, y]`) and the shape the page snapped it to, if
     * any. The samples are the same stroke's every time, only ever more.
     */
    fun frame(samples: List<DoubleArray>, predicted: List<DoubleArray>, shape: HeldShape?): Look {
        if (shape != null) {
            // The same shape while the pen rests on it: its outline (up to thousands of corners) as it was.
            if (shape != outlined) {
                outlined = shape
                outline = shape.outline()
            }
            return Stroke(emptyList(), outline, width, tool.dash, shape.closed, true, true, ink)
        }
        // No lazy brush and no prediction along the ruler, as on the page.
        tool.snap?.let { return Stroke(emptyList(), RulerSnap.project(samples, it), width, tool.dash, false, true, false, ink) }
        smooth(samples)
        val n = drawn.size
        // Smoothing trails the pen by its string, as the page's own line does, so the prediction, which leads it, is left out.
        val ahead = if (tool.stabilize > 0) emptyList() else predicted
        if (n == 0 || !ribbon) {
            val head = if (n > 1) drawn.subList(0, n - 1) else emptyList()
            val tail = if (n == 0) emptyList() else listOf(drawn[n - 1]) + ahead
            return Stroke(head, tail, width, tool.dash, false, false, false, ink)
        }
        return fill(ahead)
    }

    /** Runs the samples not read yet through the lazy brush, from where it stands: its last point. */
    private fun smooth(samples: List<DoubleArray>) {
        if (samples.size <= read) return
        val fresh = samples.subList(read, samples.size)
        val last = drawn.lastOrNull()
        if (last == null) {
            drawn.addAll(LazyBrush.smooth(fresh, tool.stabilize))
        } else {
            val out = LazyBrush.smooth(listOf(last) + fresh, tool.stabilize)
            drawn.addAll(out.subList(1, out.size))
        }
        read = samples.size
    }

    private fun fill(ahead: List<DoubleArray>): Fill {
        val n = drawn.size
        widths.update(drawn)
        fun radius(i: Int) = max(tool.size * widths.width(i), 0.5) / 2
        // Thinning (Ribbon.thin): a point closer than half a CSS px to the last one kept is left out,
        // which of the points before the last is final.
        val fresh = ArrayList<DoubleArray>()
        val freshRadii = ArrayList<Double>()
        val anchor = kept
        val anchorRadius = keptRadius
        while (thinned < n - 1) {
            val i = thinned++
            val p = drawn[i]
            val last = kept
            if (last == null || far(last, p)) {
                fresh.add(p)
                freshRadii.add(radius(i))
                kept = p
                keptRadius = radius(i)
            }
        }
        if (fresh.isNotEmpty()) settled.addAll(joined(anchor, anchorRadius, fresh, freshRadii))
        // The tail: the last point and the predicted ones at the width the stroke has reached, the last of them always kept.
        val rest = listOf(drawn[n - 1]) + ahead
        val tail = ArrayList<DoubleArray>()
        var last = kept
        for ((j, q) in rest.withIndex()) {
            if (last == null || j == rest.size - 1 || far(last, q)) {
                tail.add(q)
                last = q
            }
        }
        val tailPieces = joined(kept, keptRadius, tail, List(tail.size) { radius(n - 1) })
        // A Pencil shades with its pressure: here at the band of its mean so far, untextured (its grain comes with the page's stroke).
        return Fill(settled, tailPieces, CssColor.withAlpha(color, tool.opacity * widths.shade))
    }

    companion object {
        /** The colour of a line whose tool's colour cannot be read: opaque black. */
        const val INK = 0xff000000.toInt()

        /** How far the predicted points may run ahead: inkInput.js predictedInkSamples' 16 ms and 12 CSS px. */
        const val AHEAD_MS = 16.0
        const val AHEAD_PX = 12.0

        /**
         * The predicted points the line runs on with (inkInput.js
         * predictedInkSamples): of `predicted`, `[x, y, ms ahead of the
         * event]` in order, those ahead in time, up to the first more than
         * AHEAD_MS ahead or more than AHEAD_PX from the last sample, as `[x, y]`.
         */
        fun ahead(last: DoubleArray, predicted: List<DoubleArray>): List<DoubleArray> {
            val out = ArrayList<DoubleArray>()
            for (p in predicted) {
                if (p[2] <= 0) continue
                if (p[2] > AHEAD_MS || hypot(p[0] - last[0], p[1] - last[1]) > AHEAD_PX) break
                out.add(doubleArrayOf(p[0], p[1]))
            }
            return out
        }

        private fun far(a: DoubleArray, b: DoubleArray): Boolean {
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            return dx * dx + dy * dy >= 0.25
        }

        /**
         * The pieces `points` add to a ribbon that ends at `anchor` (none: they
         * start it): their discs, and the quads from the anchor through them.
         * Ribbon.pieces puts the discs first, so the anchor's, already drawn,
         * is the first piece when it has one.
         */
        private fun joined(anchor: DoubleArray?, anchorRadius: Double, points: List<DoubleArray>, radii: List<Double>): List<Ribbon.Piece> {
            if (anchor == null) return Ribbon.pieces(points, radii.toDoubleArray())
            val all = Ribbon.pieces(listOf(anchor) + points, (listOf(anchorRadius) + radii).toDoubleArray())
            return if (anchorRadius > 0) all.subList(1, all.size) else all
        }
    }
}

/**
 * The live widths of a stroke's drawn points, as InkWidth.widths gives them
 * live (for a stroke with widths), and a Pencil's shade, the band of the mean
 * of InkWidth.opacities, kept as the points come instead of worked out over the
 * whole stroke at every frame. A point's width reads only the points up to it
 * (the Brush's speed law and start taper run along the stroke, and live it has
 * no end taper), but for the Calligraphy pen's travel, which reads the next
 * point too. So each width is worked out once, when it is final, the Brush
 * carrying its arc length and pressure from point to point; only the
 * Calligraphy pen's last is worked out again at every frame. Each number is
 * InkWidth's, by the same steps in the same order.
 */
internal class LiveWidths(private val tool: InkTool) {
    private val nib = tool.liveNib(null).toDouble()
    private val size = InkWidth.brushSize(tool.size)
    private val start = InkWidth.TAPER_START * size
    private val shades = InkWidth.hasOpacities(tool.kind, tool.brush, tool.pressure, shape = false)

    /** The final widths, of the first `kept` points; the last point's when it is not final. */
    private var widths = DoubleArray(64)
    private var kept = 0
    private var last = 1.0

    /** The Brush's arc length and speed-law pressure at the last point kept, and that point. */
    private var arc = 0.0
    private var pressure = 0.5
    private var before: InkWidth.Sample? = null

    /** The Pencil's opacities summed, of the first `shaded` points. */
    private var opacitySum = 0.0
    private var shaded = 0

    /** Reads the points drawn since the last call: the same stroke's every time, only ever more. */
    fun update(drawn: List<DoubleArray>) {
        val n = drawn.size
        if (n == 0) return
        // Whether the stroke reads its samples' pressure (finishStroke.js hasPressure), by its first.
        val reads = tool.pressure && drawn[0].size > 2
        // Every width is final once its point is drawn, but the Calligraphy pen's, once the next one is.
        val ready = if (tool.brush == Brush.CALLIGRAPHY) n - 1 else n
        while (kept < ready) {
            if (kept == widths.size) widths = widths.copyOf(2 * widths.size)
            widths[kept] = measure(drawn, kept, reads)
            kept++
        }
        if (kept < n) last = measure(drawn, n - 1, reads)
        while (shades && shaded < n) {
            opacitySum += InkWidth.round3(InkWidth.opacity(InkWidth.sample(drawn[shaded])))
            shaded++
        }
    }

    /** The width multiplier of drawn point `i`, of those read. */
    fun width(i: Int): Double = if (i < kept) widths[i] else last

    /** The mean of a Pencil's opacities so far, or null: only a Pencil with pressure shades. */
    val opacity: Double? get() = if (shaded > 0) opacitySum / shaded else null

    /** The shade the line is drawn at: the band of `opacity`, else 1. */
    val shade: Double get() = opacity?.let(InkWidth::opacityBand) ?: 1.0

    /** Point `i`'s width; the Brush's in order, one point after the other. */
    private fun measure(drawn: List<DoubleArray>, i: Int, reads: Boolean): Double {
        val s = InkWidth.sample(drawn[i])
        return when (tool.brush) {
            Brush.CALLIGRAPHY -> {
                // Its travel reads the points beside it (InkWidth.travel), so just those.
                val from = max(i - 1, 0)
                val near = (from..min(i + 1, drawn.size - 1)).map { InkWidth.sample(drawn[it]) }
                val at = if (reads) s else InkWidth.Sample(s.x, s.y, 0.5, null)
                InkWidth.round3(InkWidth.calligraphy(at, InkWidth.travel(near, i - from), nib))
            }
            Brush.BRUSH -> {
                before?.let { b ->
                    val d = hypot(s.x - b.x, s.y - b.y)
                    arc += d
                    if (!reads) pressure = InkWidth.simulatedStep(pressure, d, size)
                }
                before = s
                InkWidth.brushWidth(if (reads) s else InkWidth.Sample(s.x, s.y, pressure, null), InkWidth.startTaper(arc, start))
            }
            else -> InkWidth.round3(InkWidth.law(tool.brush, s) ?: 1.0)
        }
    }
}
