package com.gammapdf.gamma.pen

import kotlin.math.hypot

/**
 * The lazy brush behind a preset's smoothing, as the live line draws it (the
 * web's frontend/src/ink/stabilizer.js), ported from
 * ipad/GammaIPad/Pencil/LiveStrokeView.swift LazyBrush: the pen pulls the drawn
 * point behind it on a string, so a shaky hand draws a calm line. Without the
 * web's cusp rule and its catch-up at the lift: the committed stroke is the
 * web's, smoothed from the same samples, and the live line only has to come
 * close.
 */
object LazyBrush {
    private val RADII = doubleArrayOf(0.0, 3.0, 6.0, 10.0, 16.0)

    /** The string's length per level, in CSS px (stabilizer.js STABILIZE_RADII, without its /k). */
    val radii: DoubleArray get() = RADII.copyOf()

    /**
     * The samples (`[x, y, …]`) as the brush draws them: the first where it
     * is; then each sample farther from the brush than the string moves the
     * brush toward it, to the string's length short of it, and comes out with
     * the brush's x and y, the rest of it unchanged; a sample within the string
     * moves nothing and is left out. Level 0, and a level without a string,
     * give the samples back.
     */
    fun smooth(samples: List<DoubleArray>, level: Int): List<DoubleArray> {
        val first = samples.firstOrNull()
        if (level !in RADII.indices || RADII[level] <= 0 || first == null || first.size < 2) return samples
        val string = RADII[level]
        var bx = first[0]
        var by = first[1]
        val out = ArrayList<DoubleArray>(samples.size)
        out.add(first)
        for (i in 1 until samples.size) {
            val sample = samples[i]
            if (sample.size < 2) continue
            val dx = sample[0] - bx
            val dy = sample[1] - by
            val d = hypot(dx, dy)
            if (d <= string) continue
            val pull = (d - string) / d
            bx += dx * pull
            by += dy * pull
            out.add(sample.copyOf().also { it[0] = bx; it[1] = by })
        }
        return out
    }
}
