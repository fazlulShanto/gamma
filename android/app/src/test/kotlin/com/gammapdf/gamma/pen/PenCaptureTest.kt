package com.gammapdf.gamma.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PenCapture: claims, the hold's timer and answers, and what a lift hands back, on the events' clock. */
class PenCaptureTest {
    private class Calls : PenCapture.Output {
        class Preview(val samples: List<DoubleArray>, val predicted: List<DoubleArray>, val shape: HeldShape?)
        class Stroke(val pointerId: Int, val samples: List<DoubleArray>, val held: Int?)

        val previews = ArrayList<Preview>()
        val strokes = ArrayList<Stroke>()
        val abandons = ArrayList<Int>()
        val holds = ArrayList<Pair<Int, List<DoubleArray>>>()
        var drops = 0
        val timers = ArrayList<Double?>()

        override fun preview(samples: List<DoubleArray>, predicted: List<DoubleArray>, shape: HeldShape?, tool: InkTool) {
            previews.add(Preview(samples, predicted, shape))
        }

        override fun stroke(pointerId: Int, samples: List<DoubleArray>, held: Int?) {
            strokes.add(Stroke(pointerId, samples, held))
        }

        override fun abandon(pointerId: Int) {
            abandons.add(pointerId)
        }

        override fun hold(pointerId: Int, samples: List<DoubleArray>) {
            holds.add(pointerId to samples)
        }

        override fun drop() {
            drops++
        }

        override fun holdAt(time: Double?) {
            timers.add(time)
        }
    }

    private val calls = Calls()
    private val capture = PenCapture(calls)
    private val pen = InkTool(InkKind.PEN, "#1f4fd8", 3.0)

    /** `count` samples of stroke `key` along y = 200 from x0, 4 CSS px and 4 ms apart, from `t0` seconds. */
    private fun line(key: Long, x0: Double, count: Int, t0: Double, step: Double = 4.0) {
        for (i in 0 until count) capture.record(key, x0 + step * i, 200.0, 1.0, 2.0, t0 + 0.004 * i)
    }

    @Test
    fun `a claim takes the stroke that went down there while it is still down, one at a time`() {
        line(1, 100.0, 1, 10.0)
        assertEquals(1L, capture.claim(7, 101.0, 199.0, pen, 10.01))
        assertEquals(1L, capture.captured)
        assertEquals(1, calls.previews.size)
        assertEquals(1, calls.previews[0].samples.size)
        // The hold is due the delay after the stillness anchor's sample.
        assertEquals(10.6, calls.timers.last()!!, 1e-9)
        line(2, 300.0, 1, 10.02)
        assertNull(capture.claim(8, 300.0, 200.0, pen, 10.03))
    }

    @Test
    fun `a claim for a stroke that ended, or none near, takes nothing`() {
        line(1, 100.0, 3, 10.0)
        capture.lifted(1, 10.05)
        assertNull(capture.claim(7, 100.0, 200.0, pen, 10.06))
        line(2, 100.0, 1, 11.0)
        assertNull(capture.claim(7, 300.0, 260.0, pen, 11.01))
        assertTrue(calls.strokes.isEmpty())
        assertTrue(calls.previews.isEmpty())
    }

    @Test
    fun `the line follows the pen, and the lift hands back every sample, drawn once more without prediction`() {
        line(1, 100.0, 2, 10.0)
        capture.claim(7, 100.0, 200.0, pen, 10.01)
        capture.record(1, 112.0, 200.0, 1.0, 2.0, 10.012)
        capture.moved(1, listOf(doubleArrayOf(115.0, 200.0)))
        assertEquals(3, calls.previews.last().samples.size)
        assertEquals(1, calls.previews.last().predicted.size)
        // Another stroke's samples move nothing.
        line(2, 400.0, 1, 10.013)
        capture.moved(2, listOf(doubleArrayOf(1.0, 1.0)))
        assertEquals(2, calls.previews.size)
        capture.record(1, 116.0, 200.0, 0.0, 2.0, 10.016)
        capture.lifted(1, 10.016)
        assertNull(capture.captured)
        assertTrue(calls.previews.last().predicted.isEmpty())
        assertNull(calls.timers.last())
        val stroke = calls.strokes.single()
        assertEquals(7, stroke.pointerId)
        assertEquals(4, stroke.samples.size)
        assertNull(stroke.held)
        // The lift's zero force keeps the last pressure.
        assertEquals(0.5, stroke.samples[3][2], 0.0)
    }

    @Test
    fun `the hold asks with exactly the samples it counted, and not early`() {
        line(1, 100.0, 1, 10.0)
        capture.claim(7, 100.0, 200.0, pen, 10.01)
        // The pen rests: tiny moves keep the anchor where it is, and the timer stays.
        capture.record(1, 101.0, 200.0, 1.0, 2.0, 10.2)
        capture.moved(1)
        assertEquals(1, calls.timers.size)
        capture.fire(10.5)
        assertTrue(calls.holds.isEmpty())
        // Fired early: set again for the same time.
        assertEquals(10.6, calls.timers.last()!!, 1e-9)
        capture.fire(10.6)
        val (pointerId, samples) = calls.holds.single()
        assertEquals(7, pointerId)
        assertEquals(2, samples.size)
        // One hold is pending at a time: more samples ask nothing.
        capture.record(1, 101.5, 200.0, 1.0, 2.0, 10.7)
        capture.moved(1)
        capture.fire(11.5)
        assertEquals(1, calls.holds.size)
    }

    @Test
    fun `a shape is drawn at once, and the lift names its hold`() {
        line(1, 0.0, 10, 10.0)
        capture.claim(7, 0.0, 200.0, pen, 10.05)
        assertTrue(capture.predicts)
        capture.fire(10.636 + 0.6)
        val asked = calls.holds.single().second
        val line = HeldShape("line", mapOf("x1" to 0.0, "y1" to 200.0, "x2" to 36.0, "y2" to 200.0))
        capture.held(7, HoldReply.Shape(line))
        assertEquals(line, calls.previews.last().shape)
        assertFalse(capture.predicts)
        capture.lifted(1, 11.4)
        assertEquals(asked.size, calls.strokes.single().held)
        assertEquals(line, calls.previews.last().shape)
    }

    @Test
    fun `a cancel takes the line away and the lift hands back nothing`() {
        line(1, 0.0, 3, 10.0)
        capture.claim(7, 0.0, 200.0, pen, 10.01)
        capture.fire(10.008 + 0.6)
        capture.held(7, HoldReply.Cancel)
        assertEquals(1, calls.drops)
        assertFalse(capture.predicts)
        val drawn = calls.previews.size
        capture.record(1, 40.0, 200.0, 1.0, 2.0, 10.7)
        capture.moved(1)
        assertEquals(drawn, calls.previews.size)
        capture.lifted(1, 10.7)
        assertEquals(drawn, calls.previews.size)
        val stroke = calls.strokes.single()
        assertTrue(stroke.samples.isEmpty())
        assertNull(stroke.held)
    }

    @Test
    fun `an answer nobody waits for changes nothing`() {
        line(1, 0.0, 3, 10.0)
        capture.claim(7, 0.0, 200.0, pen, 10.01)
        capture.held(7, HoldReply.Cancel)
        assertEquals(0, calls.drops)
        capture.fire(10.008 + 0.6)
        capture.held(8, HoldReply.Cancel)
        assertEquals(0, calls.drops)
        capture.held(7, HoldReply.Nothing)
        capture.held(7, HoldReply.Cancel)
        assertEquals(0, calls.drops)
    }

    @Test
    fun `after an answer of nothing the next hold waits for the pen to move`() {
        line(1, 0.0, 3, 10.0)
        capture.claim(7, 0.0, 200.0, pen, 10.01)
        capture.fire(10.608)
        val set = calls.timers.size
        capture.held(7, HoldReply.Nothing)
        assertEquals(set, calls.timers.size)
        capture.fire(10.9)
        assertEquals(1, calls.holds.size)
        capture.record(1, 20.0, 200.0, 1.0, 2.0, 11.0)
        capture.moved(1)
        assertEquals(11.6, calls.timers.last()!!, 1e-9)
        capture.fire(11.6)
        assertEquals(2, calls.holds.size)
        assertEquals(4, calls.holds[1].second.size)
    }

    @Test
    fun `a cancelled stroke is abandoned, and an uncaptured one can no longer be claimed`() {
        line(1, 0.0, 2, 10.0)
        line(2, 300.0, 1, 10.0)
        capture.claim(7, 0.0, 200.0, pen, 10.01)
        capture.cancelled(1, 10.02)
        assertEquals(listOf(7), calls.abandons)
        assertNull(calls.timers.last())
        assertNull(capture.captured)
        capture.cancelled(2, 10.02)
        assertNull(capture.claim(8, 300.0, 200.0, pen, 10.03))
        assertTrue(calls.strokes.isEmpty())
    }

    @Test
    fun `a reset abandons the capture and leaves nothing to claim`() {
        line(1, 0.0, 2, 10.0)
        line(2, 300.0, 1, 10.0)
        capture.claim(7, 0.0, 200.0, pen, 10.01)
        capture.reset(10.02)
        assertEquals(listOf(7), calls.abandons)
        assertNull(capture.captured)
        assertNull(capture.claim(8, 300.0, 200.0, pen, 10.03))
        // The strokes it forgot end without a word.
        capture.lifted(1, 10.04)
        capture.lifted(2, 10.04)
        assertTrue(calls.strokes.isEmpty())
    }

    @Test
    fun `a smoothing or ruler tool's line has no prediction`() {
        line(1, 0.0, 1, 10.0)
        capture.claim(7, 0.0, 200.0, pen.copy(stabilize = 2), 10.01)
        assertFalse(capture.predicts)
        capture.lifted(1, 10.02)
        line(2, 0.0, 1, 11.0)
        capture.claim(7, 0.0, 200.0, pen.copy(snap = Snap(0.0, 200.0, 0.0)), 11.01)
        assertFalse(capture.predicts)
        capture.lifted(2, 11.02)
        assertFalse(capture.predicts)
    }

    @Test
    fun `samples carry the pressure and the pose the device reports`() {
        capture.record(1, 10.0, 20.0, 0.9, 0.0, 5.0)
        capture.record(1, 11.0, 20.0, 0.9, 1.0, 5.004, Tilt.altitude(Math.PI / 4), Tilt.azimuth(0.0))
        capture.claim(7, 10.0, 20.0, pen, 5.01)
        val samples = calls.previews.single().samples
        // No pressure range: maxForce 0, which draws at 0.5.
        assertEquals(0.5, samples[0][2], 0.0)
        assertEquals(4, samples[0].size)
        assertEquals(6, samples[1].size)
        assertEquals(0.9, samples[1][2], 1e-12)
        assertEquals(45.0, samples[1][4], 1e-9)
        assertEquals(90.0, samples[1][5], 1e-9)
        assertNotNull(capture.captured)
    }
}
