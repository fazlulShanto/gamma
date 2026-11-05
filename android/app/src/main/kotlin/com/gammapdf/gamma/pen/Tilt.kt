package com.gammapdf.gamma.pen

import kotlin.math.PI

/**
 * A stylus's pose as the samples carry it, from MotionEvent's axes
 * (docs/dev/android.md "The pen"). The samples' `a` is UITouch's altitude:
 * 90° upright, 0 flat, where AXIS_TILT is 0 upright and π/2 flat. Their `z` is
 * PointerEvent.azimuthAngle's: the direction from the tip toward the cap, 0
 * along the screen's +x and growing clockwise as seen. AXIS_ORIENTATION is the
 * direction the stylus points, from the cap toward the tip, 0 up and π/2 right
 * (TouchInputMapper's atan2(−sin tiltX, sin tiltY)), so the cap lies along
 * (−sin θ, cos θ) in y-down space, which is (cos φ, sin φ) for φ = θ + π/2.
 * A device check compares these with the WebView's own altitudeAngle and
 * azimuthAngle for the same pose. StrokeRecorder takes the radians and writes
 * the degrees.
 */
object Tilt {
    /** The altitude in radians, π/2 upright, held to 0…π/2. NaN stays NaN. */
    fun altitude(tiltRad: Double): Double = (PI / 2 - tiltRad).coerceIn(0.0, PI / 2)

    /** The azimuth in radians, in [0, 2π). */
    fun azimuth(orientationRad: Double): Double = mod(orientationRad + PI / 2, 2 * PI)

    /** The altitude in degrees, 90 upright, held to 0…90. */
    fun altitudeDeg(tiltRad: Double): Double = (90 - Math.toDegrees(tiltRad)).coerceIn(0.0, 90.0)

    /** The azimuth in degrees, in [0, 360). */
    fun azimuthDeg(orientationRad: Double): Double = mod(Math.toDegrees(orientationRad) + 90, 360.0)
}
