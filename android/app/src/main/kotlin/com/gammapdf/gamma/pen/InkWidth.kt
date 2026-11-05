package com.gammapdf.gamma.pen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The brush table of the web's frontend/src/ink/finishStroke.js (BRUSHES,
 * brushWidths, brushOpacities), ported from ipad/GammaIPad/Pencil/InkWidth.swift
 * for the live line, so the line under the pen is as wide as the stroke the
 * page commits. The web's table stays the one law: the page bakes the
 * committed stroke's widths from the samples the shell hands back, and these
 * only draw the line until it has. Pure, and held to every row of
 * tests/shared/inkwidth.json, as the web, the server and the iPad are.
 *
 * A sample's `w` multiplies the tool's size into the width drawn there; a
 * Pencil's `o` multiplies its opacity. A stroke has widths when its brush has a
 * width law for it: a pen's Pen, Brush, Pencil or Calligraphy pen (not the
 * Ballpoint), with pressure (only the Brush without, by its speed law, and the
 * Calligraphy pen, by its travel against the nib alone), and never a
 * highlighter or a shape (a held shape is even end to end, whatever its brush).
 *
 * Each law takes samples as Sample, p and a absent where not reported; the
 * overloads on host samples (`[x, y, p, t]` or `[x, y, p, t, a, z]`, as
 * StrokeRecorder and LazyBrush give them) read them through `sample`.
 */
object InkWidth {
    /**
     * One sample as a law reads it: x and y in one unit (the Brush's tapers
     * and its speed law measure lengths in sizes of it), p the pressure, 0…1,
     * and a the altitude in degrees, each null when not reported.
     */
    data class Sample(val x: Double, val y: Double, val p: Double?, val a: Double?)

    /** A direction of travel. */
    data class Travel(val dx: Double, val dy: Double)

    /** The Brush's tapers, in sizes of arc length (each at most half the stroke), and its multiplier's range. */
    const val TAPER_START = 2.0
    const val TAPER_END = 3.0
    const val BRUSH_MIN = 0.1
    const val BRUSH_MAX = 2.0

    /** perfect-freehand's simulatePressure step (its RATE_OF_PRESSURE_CHANGE). */
    const val SIMULATE_RATE = 0.275

    /** The Calligraphy pen's hairline along its nib, in sizes: the law's floor (ink.js NIB_HAIR). */
    const val NIB_HAIR = 0.12

    /** A host sample as a law reads it. */
    fun sample(values: DoubleArray): Sample = Sample(
        x = values.getOrElse(0) { 0.0 },
        y = values.getOrElse(1) { 0.0 },
        p = if (values.size > 2) values[2] else null,
        a = if (values.size > 4) values[4] else null,
    )

    /**
     * A brush's width law at one sample, before the Brush's tapers and its
     * range: the Pen 1 + (p − 0.5), the Brush 2p, the Pencil the Pen's grown by
     * up to three times as it leans from upright (an altitude of 90°, the
     * default) to flat, capped at 4. The Ballpoint has none (an even line).
     */
    fun law(brush: Brush, s: Sample): Double? {
        val p = pressureOf(s)
        return when (brush) {
            Brush.PEN -> clamp(1 + (p - 0.5), 0.0, 2.0)
            Brush.MONOLINE -> null
            Brush.BRUSH -> 2 * p
            Brush.PENCIL -> {
                val lean = 1 - clamp(s.a ?: 90.0, 0.0, 90.0) / 90
                clamp((1 + (p - 0.5)) * (1 + 2 * lean), 0.0, 4.0)
            }
            Brush.CALLIGRAPHY -> calligraphy(s, null, 45.0)
        }
    }

    /**
     * The Calligraphy pen's law at one sample: as wide as the nib where the
     * stroke travels across it, the hairline (NIB_HAIR) along it, the sine
     * between the travel and the nib's edge, the vector (cos nib, −sin nib) in
     * y-down space, `nib` in degrees counterclockwise as seen; no travel (a
     * tap) draws the whole nib; and a little wider with pressure, capped at 4
     * (finishStroke.js BRUSHES.calligraphy).
     */
    fun calligraphy(s: Sample, travel: Travel?, nib: Double): Double {
        val r = nib * PI / 180
        val across = travel?.let { abs(it.dx * sin(r) + it.dy * cos(r)) / hypot(it.dx, it.dy) } ?: 1.0
        val press = 0.85 + 0.3 * pressureOf(s)
        return clamp(atLeast(NIB_HAIR, across) * press, 0.0, 4.0)
    }

    /**
     * The direction a stroke travels at sample `i` (finishStroke.js travelAt):
     * from the sample before to the one after, one-sided at the ends; where
     * those two coincide, the segment ending at it, else the one starting
     * there; null where the stroke has no length.
     */
    fun travel(samples: List<Sample>, i: Int): Travel? {
        if (i !in samples.indices) return null
        val n = samples.size
        fun step(a: Int, b: Int) = Travel(samples[b].x - samples[a].x, samples[b].y - samples[a].y)
        val tries = buildList {
            add(step(max(i - 1, 0), min(i + 1, n - 1)))
            if (i > 0) add(step(i - 1, i))
            if (i < n - 1) add(step(i, i + 1))
        }
        return tries.firstOrNull { hypot(it.dx, it.dy) > 1e-9 }
    }

    /** The Pencil's opacity law: pressure darkens it. */
    fun opacity(s: Sample): Double = clamp(0.15 + 0.85 * pressureOf(s), 0.0, 1.0)

    /**
     * Whether a stroke has widths (finishStroke.js widthLaw): a pen's brush with
     * a law, not a shape, and with pressure unless it is the Brush or the
     * Calligraphy pen.
     */
    fun hasWidths(kind: InkKind, brush: Brush, pressure: Boolean, shape: Boolean): Boolean =
        kind == InkKind.PEN && !shape && brush != Brush.MONOLINE &&
            (pressure || brush == Brush.BRUSH || brush == Brush.CALLIGRAPHY)

    /** Whether a stroke shades (finishStroke.js opacityLaw): only a Pencil with pressure, and never a shape. */
    fun hasOpacities(kind: InkKind, brush: Brush, pressure: Boolean, shape: Boolean): Boolean =
        kind == InkKind.PEN && brush == Brush.PENCIL && pressure && !shape

    /**
     * The width multipliers of a stroke, one per sample to three decimals, or
     * null when it has none (`hasWidths`). The Brush's tapers run over the
     * samples' arc length: the start taper while it is under 2 sizes, the end
     * taper while the rest is under 3, each at most half the stroke's length
     * and neither on a stroke of no length nor on a finished stroke of two
     * samples, which has no middle to keep, or one shorter than its size, whose
     * middle the outline skips; its pressure is the samples' own, or, without
     * pressure (`pressure` false, or samples without p), the speed law's.
     * `live` is a stroke still being written: no end taper and the start taper
     * at its full length, as the web's live canvas draws it, so the line under
     * the pen holds still. A Calligraphy pen's widths read each sample's travel
     * and `nib` (the stroke's, in degrees: the claim's, InkTool.liveNib), its
     * pressure only when the stroke reads it (else 0.5), and have no tapers.
     */
    fun widths(
        samples: List<Sample>, kind: InkKind, brush: Brush, pressure: Boolean, size: Double,
        shape: Boolean = false, live: Boolean = false, nib: Double = 45.0,
    ): DoubleArray? {
        if (!hasWidths(kind, brush, pressure, shape) || samples.isEmpty()) return null
        // Whether the stroke reads its samples' pressure (finishStroke.js hasPressure).
        val read = pressure && samples[0].p != null
        if (brush == Brush.CALLIGRAPHY) {
            return DoubleArray(samples.size) { i ->
                val s = if (read) samples[i] else Sample(samples[i].x, samples[i].y, 0.5, null)
                round3(calligraphy(s, travel(samples, i), nib))
            }
        }
        if (brush != Brush.BRUSH) return DoubleArray(samples.size) { round3(law(brush, samples[it]) ?: 1.0) }
        val size = brushSize(size)
        val simulated = if (read) null else simulatedPressure(samples, size)
        val arc = DoubleArray(samples.size)
        for (i in 1 until samples.size) {
            arc[i] = arc[i - 1] + hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
        }
        val total = arc[arc.size - 1]
        val most = if (live) Double.POSITIVE_INFINITY else if (samples.size < 3 || total < size) 0.0 else total / 2
        val start = min(TAPER_START * size, most)
        val end = min(TAPER_END * size, most)
        return DoubleArray(samples.size) { i ->
            val ts = startTaper(arc[i], start)
            val te = if (!live && total - arc[i] < end) easeOutSine((total - arc[i]) / end) else 1.0
            val s = simulated?.let { Sample(samples[i].x, samples[i].y, it[i], null) } ?: samples[i]
            brushWidth(s, min(ts, te))
        }
    }

    /** The size the Brush measures its tapers and its speed law in: at least 0.01. */
    fun brushSize(size: Double): Double = atLeast(0.01, size)

    /** The Brush's start taper at `arc` along the stroke, for a taper `start` long: easing out from 0, then 1. */
    fun startTaper(arc: Double, start: Double): Double = if (arc < start) easeOutSine(arc / start) else 1.0

    /** The Brush's width multiplier at a sample under `taper` (its tapers' smaller there), in its range, to three decimals. */
    fun brushWidth(s: Sample, taper: Double): Double = round3(clamp((law(Brush.BRUSH, s) ?: 0.0) * taper, BRUSH_MIN, BRUSH_MAX))

    /** `widths` of host samples; `nib` as InkTool.liveNib gives it. */
    @JvmName("hostWidths")
    fun widths(
        samples: List<DoubleArray>, kind: InkKind, brush: Brush, pressure: Boolean, size: Double,
        shape: Boolean = false, live: Boolean = false, nib: Int = 45,
    ): DoubleArray? = widths(samples.map(::sample), kind, brush, pressure, size, shape, live, nib.toDouble())

    /** The opacity multipliers of a stroke, to three decimals, or null: only a Pencil with pressure shades (finishStroke.js opacityLaw). */
    fun opacities(samples: List<Sample>, kind: InkKind, brush: Brush, pressure: Boolean, shape: Boolean = false): DoubleArray? {
        if (!hasOpacities(kind, brush, pressure, shape) || samples.isEmpty()) return null
        return DoubleArray(samples.size) { round3(opacity(samples[it])) }
    }

    /** `opacities` of host samples. */
    @JvmName("hostOpacities")
    fun opacities(samples: List<DoubleArray>, kind: InkKind, brush: Brush, pressure: Boolean, shape: Boolean = false): DoubleArray? =
        opacities(samples.map(::sample), kind, brush, pressure, shape)

    /** The shade a page draws an opacity multiplier at: one of three bands, rounded up (ink.js opacityBand). */
    fun opacityBand(o: Double): Double = if (o <= 0.33) 0.33 else if (o <= 0.66) 0.66 else 1.0

    /**
     * The pressure the speed law gives each sample, from the spacing alone
     * (perfect-freehand's simulatePressure; time is never read): 0.5 at the
     * first, then a step toward 1 − min(1, d / size) for a sample at distance
     * d from the one before, so slow is wide and fast is thin.
     */
    fun simulatedPressure(samples: List<Sample>, size: Double): DoubleArray {
        val out = DoubleArray(samples.size)
        var p = 0.5
        for (i in samples.indices) {
            if (i > 0) p = simulatedStep(p, hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y), size)
            out[i] = p
        }
        return out
    }

    /** The speed law's pressure at a sample `d` from the one before, whose pressure was `p`. */
    fun simulatedStep(p: Double, d: Double, size: Double): Double {
        val step = min(1.0, d / size)
        return min(1.0, p + (1 - step - p) * step * SIMULATE_RATE)
    }

    /** `simulatedPressure` of host samples. */
    @JvmName("hostSimulatedPressure")
    fun simulatedPressure(samples: List<DoubleArray>, size: Double): DoubleArray = simulatedPressure(samples.map(::sample), size)

    /** Three decimals, as the web's Math.round does them (halves up). */
    fun round3(v: Double): Double = floor(v * 1000 + 0.5) / 1000

    private fun pressureOf(s: Sample): Double = clamp(s.p ?: 0.5, 0.0, 1.0)

    private fun easeOutSine(x: Double): Double = sin(x * PI / 2)

    // Swift's max(lo, min(hi, v)), which reads a NaN as hi, where Kotlin's would keep it.
    private fun clamp(v: Double, lo: Double, hi: Double): Double = atLeast(lo, if (v < hi) v else hi)

    // Swift's max(floor, v): v unless it is below the floor (or NaN).
    private fun atLeast(floor: Double, v: Double): Double = if (v >= floor) v else floor
}
