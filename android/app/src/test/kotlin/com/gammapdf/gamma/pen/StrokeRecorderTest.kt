package com.gammapdf.gamma.pen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

/**
 * The samples a stroke hands back, with their tilt, roll and stillness anchor
 * (StrokeRecorder), the cases of ipad/GammaIPadTests/PencilTests.swift
 * StrokeRecorderTests.
 */
class StrokeRecorderTest {
    @Test
    fun samplesAreXYPressureAndMillisecondsFromTheFirst() {
        val stroke = StrokeRecorder()
        stroke.add(10.0, 20.0, force = 2.0, maxForce = 4.0, time = 100.0)
        stroke.add(12.0, 21.0, force = 3.0, maxForce = 4.0, time = 100.008)
        assertEquals(2, stroke.samples.size)
        assertArrayEquals(doubleArrayOf(10.0, 20.0, 0.5, 0.0), stroke.samples[0], 0.0)
        assertEquals(0.75, stroke.samples[1][2], 0.0)
        assertEquals(8.0, stroke.samples[1][3], 1e-6)
        assertArrayEquals(doubleArrayOf(12.0, 21.0), stroke.samples[1].copyOf(2), 0.0)
    }

    @Test
    fun theLiftKeepsTheLastPressure() {
        val stroke = StrokeRecorder()
        stroke.add(0.0, 0.0, 1.0, 4.0, 0.0)
        stroke.add(1.0, 0.0, 0.0, 4.0, 0.01)
        assertEquals(0.25, stroke.samples[1][2], 0.0)
    }

    @Test
    fun timeNeverGoesBackAndForceIsCapped() {
        val stroke = StrokeRecorder()
        stroke.add(0.0, 0.0, 9.0, 4.0, 1.0)
        stroke.add(1.0, 0.0, 1.0, 4.0, 0.99)
        assertEquals(1.0, stroke.samples[0][2], 0.0)
        assertEquals(0.0, stroke.samples[1][3], 0.0)
    }

    @Test
    fun aTouchWithoutForceDrawsAtHalfPressure() {
        val stroke = StrokeRecorder()
        stroke.add(0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(0.5, stroke.samples[0][2], 0.0)
        // a pen with no pressure sensor: maxForce 0, whatever it reports
        stroke.add(1.0, 0.0, 1.0, 0.0, 0.01)
        assertEquals(0.5, stroke.samples[1][2], 0.0)
    }

    @Test
    fun thePensTiltComesInDegrees() {
        val stroke = StrokeRecorder()
        stroke.add(10.0, 20.0, 2.0, 4.0, 0.0, altitude = PI / 4, azimuth = PI / 2)
        val first = stroke.samples[0]
        assertEquals("[x, y, p, t, a, z]", 6, first.size)
        assertArrayEquals(doubleArrayOf(10.0, 20.0, 0.5, 0.0), first.copyOf(4), 0.0)
        assertEquals("the altitude", 45.0, first[4], 1e-9)
        assertEquals("the azimuth", 90.0, first[5], 1e-9)
        stroke.add(11.0, 20.0, 2.0, 4.0, 0.004, altitude = 0.5)
        assertEquals("without both angles, four values, as an older shell sends", 4, stroke.samples[1].size)
    }

    @Test
    fun theRollComesSeventhInDegreesClockwise() {
        val stroke = StrokeRecorder()
        stroke.add(10.0, 20.0, 2.0, 4.0, 0.0, altitude = PI / 4, azimuth = 0.0, roll = PI / 6)
        assertEquals("[x, y, p, t, a, z, r]", 7, stroke.samples[0].size)
        assertEquals("the roll in degrees", 30.0, stroke.samples[0][6], 1e-9)
        stroke.add(11.0, 20.0, 2.0, 4.0, 0.004, altitude = PI / 4, azimuth = 0.0, roll = -PI / 2)
        assertEquals("into [0, 360)", 270.0, stroke.samples[1][6], 1e-9)
        stroke.add(12.0, 20.0, 2.0, 4.0, 0.008, altitude = PI / 4, azimuth = 0.0)
        assertEquals("no roll reported, as on Android: six values", 6, stroke.samples[2].size)
        stroke.add(13.0, 20.0, 2.0, 4.0, 0.012, roll = 1.0)
        assertEquals("a roll without the tilt is not written: the seventh value follows the sixth", 4, stroke.samples[3].size)
    }

    @Test
    fun aRollJustBelowZeroComesAsZero() {
        // −1e-17 rad is −5.7e-16°, and that + 360 rounds to 360 itself:
        // mod's second remainder folds it to 0, in the contract's [0, 360)
        val stroke = StrokeRecorder()
        stroke.add(10.0, 20.0, 2.0, 4.0, 0.0, altitude = PI / 4, azimuth = 0.0, roll = -1e-17)
        assertEquals("never 360", 0.0, stroke.samples[0][6], 0.0)
    }

    @Test
    fun theStillnessAnchorMovesOnlyPastTheSlop() {
        val stroke = StrokeRecorder()
        assertEquals(3.0, stroke.holdSlop, 0.0)
        val xs = listOf(0.0, 1.0, 2.0, 3.0, 3.5, 3.2, 6.6, 7.0)
        for ((i, x) in xs.withIndex()) {
            stroke.add(x, 0.0, 1.0, 2.0, 10 + i * 0.01)
            // 3 is not past the slop from 0; 3.5 is, and becomes the anchor; 6.6 is 3.1 from it
            val anchor = if (i < 4) 0 else if (i < 6) 4 else 6
            assertEquals("after x = $x", anchor, stroke.stillSince)
        }
        assertEquals("the anchor's own time", 10.06, stroke.stillTime, 1e-9)
    }

    @Test
    fun theSlopIsADistanceFromTheAnchor() {
        val stroke = StrokeRecorder()
        stroke.add(0.0, 0.0, 1.0, 2.0, 0.0)
        stroke.add(2.2, 2.2, 1.0, 2.0, 0.01) // 3.11 away, though under 3 on each axis
        assertEquals(1, stroke.stillSince)
        // creeping 0.9 CSS px a sample from there: each within 3 of the one before, not of the anchor
        for (i in 1..4) stroke.add(2.2 + 0.9 * i, 2.2, 1.0, 2.0, 0.01 + i * 0.01)
        assertEquals("the fourth step is 3.6 from the anchor", 5, stroke.stillSince)
    }

    @Test
    fun theSamplesTakenKeepWhatTheyHad() {
        // a hold sends the samples its ask counted, though the pen writes on
        val stroke = StrokeRecorder()
        stroke.add(0.0, 0.0, 1.0, 2.0, 0.0)
        val taken = stroke.samples
        stroke.add(5.0, 0.0, 1.0, 2.0, 0.01)
        assertEquals(1, taken.size)
        assertEquals(2, stroke.samples.size)
        assertEquals(2, stroke.size)
    }
}
