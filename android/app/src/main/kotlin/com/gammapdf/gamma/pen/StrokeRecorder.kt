package com.gammapdf.gamma.pen

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One stroke's samples as the web's ink layer takes them, ported from
 * ipad/GammaIPad/Pencil/PencilCapture.swift StrokeRecorder: `[x, y, p, t]`, p
 * the force over the maximum (0…1), t in ms since the first sample and never
 * going back, then, when the pen reports its tilt, `a` and `z`, its altitude
 * and azimuth in degrees (Tilt). The iPad adds a seventh value, the barrel
 * roll, which no Android stylus reports. The lift reports no force, so a zero
 * keeps the last pressure, as the web's own sampling does; a device without a
 * pressure sensor passes `maxForce` 0, which draws at 0.5.
 *
 * It also keeps the stroke's stillness anchor (shapes.js stillSince): the
 * latest sample that landed more than `holdSlop` CSS px from the anchor before
 * it, at first the first sample, so every sample since the anchor is within
 * the slop of it. HoldWatch asks the page about a hold from it.
 */
class StrokeRecorder(val holdSlop: Double = 3.0) {
    private val recorded = ArrayList<DoubleArray>()
    private var copy: List<DoubleArray>? = null
    private var start = Double.NaN

    /**
     * The samples so far, as a list of their own: one taken before a later
     * `add` keeps what it had, so a `hold` carries exactly the samples its ask
     * counted. The arrays are never changed.
     */
    val samples: List<DoubleArray>
        get() = copy ?: ArrayList(recorded).also { copy = it }

    /** The number of samples so far. */
    val size: Int get() = recorded.size

    /** The stillness anchor's index in `samples`. */
    var stillSince = 0
        private set

    /** The anchor sample's time, on the events' clock (seconds). */
    var stillTime = 0.0
        private set

    /**
     * One sample: `x`, `y` in viewport CSS px, `time` in seconds, `altitude`
     * and `azimuth` in radians as Tilt gives them (both, or neither is written),
     * `roll` in radians (the iPad's; written only after the two angles).
     */
    fun add(
        x: Double, y: Double, force: Double, maxForce: Double, time: Double,
        altitude: Double? = null, azimuth: Double? = null, roll: Double? = null,
    ) {
        if (start.isNaN()) start = time
        val previous = recorded.lastOrNull()
        val measured = if (maxForce > 0) force / maxForce else 0.5
        val p = if (measured > 0) min(1.0, measured) else previous?.get(2) ?: 0.5
        val t = max(previous?.get(3) ?: 0.0, (time - start) * 1000)
        val sample = when {
            altitude == null || azimuth == null -> doubleArrayOf(x, y, p, t)
            roll == null || !roll.isFinite() -> doubleArrayOf(x, y, p, t, altitude * 180 / Math.PI, azimuth * 180 / Math.PI)
            else -> doubleArrayOf(x, y, p, t, altitude * 180 / Math.PI, azimuth * 180 / Math.PI, mod(roll * 180 / Math.PI, 360.0))
        }
        if (recorded.isEmpty()) {
            stillSince = 0
            stillTime = time
        } else {
            val anchor = recorded[stillSince]
            if (hypot(x - anchor[0], y - anchor[1]) > holdSlop) {
                stillSince = recorded.size
                stillTime = time
            }
        }
        recorded.add(sample)
        copy = null
    }
}
