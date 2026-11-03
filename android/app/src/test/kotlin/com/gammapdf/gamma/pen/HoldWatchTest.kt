package com.gammapdf.gamma.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the hold is asked, with the time given, as the pen layer's timer asks
 * HoldWatch: the cases of ipad/GammaIPadTests/PencilTests.swift HoldWatchTests.
 */
class HoldWatchTest {
    /**
     * A stroke that moves 4 CSS px a sample from time 100 every 4 ms to x = 36
     * (each sample a new anchor, the last at 100.036), then rests there,
     * within a px, for `rest` more samples.
     */
    private fun stroke(rest: Int): StrokeRecorder {
        val stroke = StrokeRecorder()
        for (i in 0 until 10) stroke.add(i * 4.0, 50.0, 1.0, 2.0, 100 + i * 0.004)
        for (i in 0 until rest) stroke.add(36 + if (i % 2 == 0) 0.5 else -0.5, 50.0, 1.0, 2.0, 100.04 + i * 0.004)
        return stroke
    }

    @Test
    fun theHoldIsDueTheDelayAfterTheAnchorsSample() {
        val watch = HoldWatch()
        val due = watch.due(stroke(10))!!
        assertEquals(9, due.first)
        assertEquals(100.036 + watch.delay, due.second, 1e-9)
        assertEquals(0.6, watch.delay, 0.0)
    }

    @Test
    fun itAsksOnceWhenDue() {
        val watch = HoldWatch()
        val recorder = stroke(10)
        assertNull("not yet", watch.fire(recorder, 100.5))
        assertNotNull(watch.fire(recorder, 100.636))
        assertNull("one hold at a time", watch.due(recorder))
        assertNull("and no second one while it is asked", watch.fire(recorder, 101.0))
    }

    @Test
    fun theAskSaysWhatAShapeFollowsFrom() {
        val watch = HoldWatch()
        val recorder = stroke(10)
        val fired = watch.fire(recorder, 100.7)
        val ask = watch.pending!!
        assertEquals(fired, ask)
        assertEquals(9, ask.anchor)
        assertEquals("every sample so far", 20, ask.n)
        assertEquals("the last one, where the pen stands", 35.5, ask.fromX, 0.0)
        assertEquals(50.0, ask.fromY, 0.0)
        assertEquals("the anchor's t + 600 ms", recorder.samples[9][3] + 600, ask.tSnap, 1e-9)
    }

    @Test
    fun anAnswerOfNothingWaitsForTheAnchorToMove() {
        val watch = HoldWatch()
        val recorder = stroke(10)
        assertNotNull(watch.fire(recorder, 100.7))
        assertEquals(9, watch.answered(HoldReply.Nothing)?.anchor)
        assertNull("this still spell has been asked", watch.due(recorder))
        assertNull(watch.fire(recorder, 102.0))
        recorder.add(60.0, 50.0, 1.0, 2.0, 101.0)
        val due = watch.due(recorder)
        assertNotNull("the pen moved on: its next stop can ask again", due)
        assertEquals(20, due!!.first)
        assertEquals(101.6, due.second, 1e-9)
        assertNotNull(watch.fire(recorder, 101.6))
    }

    @Test
    fun aShapeOrACancelEndsTheHolds() {
        val shape = HeldShape("line", mapOf("x1" to 0.0, "y1" to 50.0, "x2" to 36.0, "y2" to 50.0))
        for (reply in listOf(HoldReply.Shape(shape), HoldReply.Cancel)) {
            val watch = HoldWatch()
            val recorder = stroke(10)
            assertNotNull(watch.fire(recorder, 100.7))
            assertNotNull(watch.answered(reply))
            assertTrue(watch.settled)
            recorder.add(80.0, 50.0, 1.0, 2.0, 101.0)
            assertNull("no more holds after $reply", watch.due(recorder))
            assertNull(watch.fire(recorder, 103.0))
        }
    }

    @Test
    fun anAnswerNobodyWaitsForChangesNothing() {
        val watch = HoldWatch()
        val recorder = stroke(10)
        assertNull("nothing was asked (an answer after the lift)", watch.answered(HoldReply.Cancel))
        assertFalse(watch.settled)
        assertNotNull(watch.due(recorder))
        assertNotNull(watch.fire(recorder, 100.7))
        assertNotNull(watch.answered(HoldReply.Nothing))
        assertNull("a second answer", watch.answered(HoldReply.Cancel))
        assertFalse(watch.settled)
    }

    @Test
    fun anAnchorThatMovedWhileAskingIsDueAtOnce() {
        val watch = HoldWatch()
        val recorder = stroke(10)
        assertNotNull(watch.fire(recorder, 100.7))
        recorder.add(50.0, 50.0, 1.0, 2.0, 100.71) // moved on while the page answered
        assertNull("still asking", watch.due(recorder))
        assertNotNull(watch.answered(HoldReply.Nothing))
        val due = watch.due(recorder)!!
        assertEquals(20, due.first)
        assertEquals(101.31, due.second, 1e-9)
    }

    @Test
    fun aStillPenFromTheDownIsAskedToo() {
        // the paste hold: the page answers it from the samples
        val stroke = StrokeRecorder()
        for (i in 0 until 50) stroke.add(200 + if (i % 3 == 0) 0.4 else 0.0, 300.0, 1.0, 2.0, 5 + i * 0.008)
        val due = HoldWatch().due(stroke)!!
        assertEquals(0, due.first)
        assertEquals(5.6, due.second, 1e-9)
    }

    @Test
    fun aRecorderWithNoSamplesAsksNothing() {
        val watch = HoldWatch()
        assertNull(watch.due(StrokeRecorder()))
        assertNull(watch.fire(StrokeRecorder(), 10.0))
    }
}
