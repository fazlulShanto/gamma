package com.gammapdf.gamma.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** A stylus's MotionEvent axes as the samples' altitude and azimuth (Tilt). */
class TiltTest {
    @Test
    fun theAltitudeIsNinetyUpright() {
        assertEquals("AXIS_TILT 0: upright", 90.0, Tilt.altitudeDeg(0.0), 0.0)
        assertEquals("π/2: flat", 0.0, Tilt.altitudeDeg(PI / 2), 1e-12)
        assertEquals(45.0, Tilt.altitudeDeg(PI / 4), 1e-12)
        assertEquals(30.0, Tilt.altitudeDeg(PI / 3), 1e-12)
        assertEquals("held to 0…90", 90.0, Tilt.altitudeDeg(-0.1), 0.0)
        assertEquals(0.0, Tilt.altitudeDeg(2.0), 0.0)
        assertEquals(PI / 2, Tilt.altitude(0.0), 0.0)
        assertEquals(PI / 4, Tilt.altitude(PI / 4), 1e-15)
        assertEquals(0.0, Tilt.altitude(PI), 0.0)
    }

    @Test
    fun theAzimuthPointsFromTheTipToTheCapClockwiseFromPlusX() {
        // AXIS_ORIENTATION is where the pen points, cap to tip: 0 up, π/2 right
        assertEquals("pointing up: the cap toward 6 o'clock", 90.0, Tilt.azimuthDeg(0.0), 1e-12)
        assertEquals("pointing right: the cap toward 9 o'clock", 180.0, Tilt.azimuthDeg(PI / 2), 1e-12)
        assertEquals("pointing left (the cap leaning right): 0", 0.0, Tilt.azimuthDeg(-PI / 2), 1e-12)
        assertEquals("pointing down: the cap toward 12 o'clock", 270.0, Tilt.azimuthDeg(PI), 1e-12)
        assertEquals(270.0, Tilt.azimuthDeg(-PI), 1e-12)
        val justBelow = Tilt.azimuthDeg(-PI / 2 - 1e-9)
        assertTrue("in [0, 360): $justBelow", justBelow >= 0 && justBelow < 360)
        assertEquals(360.0, justBelow, 1e-6)
        assertEquals(PI, Tilt.azimuth(PI / 2), 1e-15)
        assertEquals(0.0, Tilt.azimuth(-PI / 2), 0.0)
        assertEquals(3 * PI / 2, Tilt.azimuth(-PI), 1e-15)
    }

    @Test
    fun theRecorderWritesTheDegrees() {
        val stroke = StrokeRecorder()
        stroke.add(0.0, 0.0, 0.5, 1.0, 0.0, altitude = Tilt.altitude(PI / 6), azimuth = Tilt.azimuth(PI / 2))
        assertEquals(Tilt.altitudeDeg(PI / 6), stroke.samples[0][4], 1e-9)
        assertEquals(Tilt.azimuthDeg(PI / 2), stroke.samples[0][5], 1e-9)
    }
}
