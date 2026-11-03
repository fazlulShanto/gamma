package com.gammapdf.gamma.pen

import android.view.MotionEvent
import com.gammapdf.gamma.pen.PenRouter.Companion.ACTION_CANCEL
import com.gammapdf.gamma.pen.PenRouter.Companion.ACTION_DOWN
import com.gammapdf.gamma.pen.PenRouter.Companion.ACTION_MOVE
import com.gammapdf.gamma.pen.PenRouter.Companion.ACTION_POINTER_DOWN
import com.gammapdf.gamma.pen.PenRouter.Companion.ACTION_POINTER_UP
import com.gammapdf.gamma.pen.PenRouter.Companion.ACTION_UP
import com.gammapdf.gamma.pen.PenRouter.Companion.TOOL_TYPE_ERASER
import com.gammapdf.gamma.pen.PenRouter.Companion.TOOL_TYPE_STYLUS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PenRouter: what the page gets of a touch stream, and which stylus pointers are recorded. */
class PenRouterTest {
    private val router = PenRouter()

    /** The pointers down, in the event's order: id to tool type. */
    private val down = LinkedHashMap<Int, Int>()

    private fun event(action: Int, id: Int, tool: Int = FINGER, buttons: Int = 0, canceled: Boolean = false, device: Int = 1): PenRouter.Route {
        if (action == ACTION_DOWN || action == ACTION_POINTER_DOWN) down[id] = tool
        val ids = down.keys.toIntArray()
        val tools = down.values.toIntArray()
        if (action == ACTION_UP || action == ACTION_POINTER_UP) down.remove(id)
        if (action == ACTION_CANCEL) down.clear()
        return router.route(action, id, ids, tools, buttons, canceled, device)
    }

    private fun move(device: Int = 1) = event(ACTION_MOVE, down.keys.first(), device = device)

    private fun assertForward(forward: PenRouter.Forward?, action: Int, ids: IntArray, index: Int = 0, whole: Boolean = false, fresh: Boolean = false) {
        assertNotNull(forward)
        forward!!
        assertEquals("action", action, forward.action)
        assertArrayEquals("ids", ids, forward.ids)
        assertEquals("index", index, forward.index)
        assertEquals("whole", whole, forward.whole)
        assertEquals("fresh", fresh, forward.fresh)
    }

    @Test
    fun `the values are android's`() {
        assertEquals(MotionEvent.ACTION_DOWN, ACTION_DOWN)
        assertEquals(MotionEvent.ACTION_UP, ACTION_UP)
        assertEquals(MotionEvent.ACTION_MOVE, ACTION_MOVE)
        assertEquals(MotionEvent.ACTION_CANCEL, ACTION_CANCEL)
        assertEquals(MotionEvent.ACTION_POINTER_DOWN, ACTION_POINTER_DOWN)
        assertEquals(MotionEvent.ACTION_POINTER_UP, ACTION_POINTER_UP)
        assertEquals(MotionEvent.TOOL_TYPE_UNKNOWN, PenRouter.TOOL_TYPE_UNKNOWN)
        assertEquals(MotionEvent.TOOL_TYPE_STYLUS, TOOL_TYPE_STYLUS)
        assertEquals(MotionEvent.TOOL_TYPE_ERASER, TOOL_TYPE_ERASER)
        assertEquals(MotionEvent.TOOL_TYPE_FINGER, FINGER)
        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, PenRouter.BUTTON_STYLUS_PRIMARY)
    }

    @Test
    fun `fingers go to the page as they came, unrecorded`() {
        assertForward(event(ACTION_DOWN, 0).forward, ACTION_DOWN, intArrayOf(0), whole = true)
        assertForward(move().forward, ACTION_MOVE, intArrayOf(0), whole = true)
        val second = event(ACTION_POINTER_DOWN, 1)
        assertForward(second.forward, ACTION_POINTER_DOWN, intArrayOf(0, 1), index = 1, whole = true)
        assertArrayEquals(longArrayOf(0, 0), second.tokens)
        assertForward(event(ACTION_POINTER_UP, 0).forward, ACTION_POINTER_UP, intArrayOf(0, 1), index = 0, whole = true)
        assertForward(event(ACTION_UP, 1).forward, ACTION_UP, intArrayOf(1), whole = true)
        assertFalse(router.active)
    }

    @Test
    fun `each stylus down is recorded under a token of its own`() {
        val first = event(ACTION_DOWN, 0, STYLUS)
        assertForward(first.forward, ACTION_DOWN, intArrayOf(0), whole = true)
        assertFalse(first.forward!!.erasers[0])
        val token = first.tokens[0]
        assertNotEquals(0L, token)
        assertEquals(token, move().tokens[0])
        assertEquals(token, event(ACTION_UP, 0).tokens[0])
        // The next touch has the same pointer id, and a token of its own.
        val next = event(ACTION_DOWN, 0, STYLUS).tokens[0]
        assertNotEquals(0L, next)
        assertNotEquals(token, next)
    }

    @Test
    fun `tokens follow the event's pointers`() {
        event(ACTION_DOWN, 4)
        val pen = event(ACTION_POINTER_DOWN, 2, STYLUS)
        assertEquals(0L, pen.tokens[0])
        assertNotEquals(0L, pen.tokens[1])
        assertForward(pen.forward, ACTION_POINTER_DOWN, intArrayOf(4, 2), index = 1, whole = true)
    }

    @Test
    fun `a stylus down with its primary button held is the eraser to its lift`() {
        val down = event(ACTION_DOWN, 0, STYLUS, buttons = PenRouter.BUTTON_STYLUS_PRIMARY)
        assertForward(down.forward, ACTION_DOWN, intArrayOf(0), whole = true)
        assertTrue(down.forward!!.erasers[0])
        assertEquals(0L, down.tokens[0])
        // The button let go while it writes changes nothing: it was decided at the down.
        val moved = move()
        assertTrue(moved.forward!!.erasers[0])
        assertEquals(0L, moved.tokens[0])
        assertTrue(event(ACTION_UP, 0).forward!!.erasers[0])
        val plain = event(ACTION_DOWN, 0, STYLUS)
        assertFalse(plain.forward!!.erasers[0])
        assertNotEquals(0L, plain.tokens[0])
    }

    @Test
    fun `the eraser end goes as it is, unrecorded`() {
        val down = event(ACTION_DOWN, 0, TOOL_TYPE_ERASER)
        assertForward(down.forward, ACTION_DOWN, intArrayOf(0), whole = true)
        assertFalse(down.forward!!.erasers[0])
        assertEquals(0L, down.tokens[0])
    }

    @Test
    fun `a take ends the page's stream and swallows the gesture, palms too`() {
        event(ACTION_DOWN, 0)
        val token = event(ACTION_POINTER_DOWN, 1, STYLUS).tokens[1]
        move()
        assertForward(router.take(token), ACTION_CANCEL, intArrayOf(0, 1))
        assertTrue(router.swallowing)
        val moved = move()
        assertNull(moved.forward)
        assertEquals(token, moved.tokens[1])
        val palm = event(ACTION_POINTER_DOWN, 2)
        assertNull(palm.forward)
        assertArrayEquals(longArrayOf(0, token, 0), palm.tokens)
        // A second stylus while the captured one is down is swallowed too, and never recorded.
        val other = event(ACTION_POINTER_DOWN, 3, STYLUS)
        assertNull(other.forward)
        assertEquals(0L, other.tokens[3])
        assertNull(event(ACTION_POINTER_UP, 1).forward)
        assertNull(event(ACTION_POINTER_UP, 3).forward)
        assertNull(event(ACTION_POINTER_UP, 2).forward)
        assertNull(event(ACTION_UP, 0).forward)
        assertFalse(router.swallowing)
        assertForward(event(ACTION_DOWN, 0).forward, ACTION_DOWN, intArrayOf(0), whole = true)
    }

    @Test
    fun `a stylus down after the captured one lifted starts a stream of its own`() {
        event(ACTION_DOWN, 0)
        val token = event(ACTION_POINTER_DOWN, 1, STYLUS).tokens[1]
        router.take(token)
        event(ACTION_POINTER_UP, 1)
        val next = event(ACTION_POINTER_DOWN, 2, STYLUS)
        assertForward(next.forward, ACTION_DOWN, intArrayOf(2), fresh = true)
        val second = next.tokens[1]
        assertNotEquals(0L, second)
        assertForward(move().forward, ACTION_MOVE, intArrayOf(2))
        // A palm meanwhile is still swallowed.
        assertNull(event(ACTION_POINTER_DOWN, 5).forward)
        assertForward(move().forward, ACTION_MOVE, intArrayOf(2))
        assertForward(event(ACTION_POINTER_UP, 2).forward, ACTION_UP, intArrayOf(2))
        // And the next one can be claimed in turn.
        val third = event(ACTION_POINTER_DOWN, 3, STYLUS)
        assertForward(third.forward, ACTION_DOWN, intArrayOf(3), fresh = true)
        assertForward(router.take(third.tokens[2]), ACTION_CANCEL, intArrayOf(3))
        assertNull(move().forward)
    }

    @Test
    fun `a lift with FLAG_CANCELED cancels the page's stream when it was its last pointer`() {
        event(ACTION_DOWN, 0, STYLUS)
        event(ACTION_POINTER_DOWN, 1)
        // Given whole, the event goes as it came, its flag with it.
        assertForward(event(ACTION_POINTER_UP, 1, canceled = true).forward, ACTION_POINTER_UP, intArrayOf(0, 1), index = 1, whole = true)
        assertForward(event(ACTION_UP, 0).forward, ACTION_UP, intArrayOf(0), whole = true)
        // A stream of its own whose stylus is cancelled ends in ACTION_CANCEL.
        event(ACTION_DOWN, 0)
        router.take(event(ACTION_POINTER_DOWN, 1, STYLUS).tokens[1])
        event(ACTION_POINTER_UP, 1)
        event(ACTION_POINTER_DOWN, 2, STYLUS)
        assertForward(event(ACTION_POINTER_UP, 2, canceled = true).forward, ACTION_CANCEL, intArrayOf(2))
    }

    @Test
    fun `ACTION_CANCEL reaches the page for the pointers it has`() {
        event(ACTION_DOWN, 0, STYLUS)
        event(ACTION_POINTER_DOWN, 1)
        val cancel = event(ACTION_CANCEL, 0)
        assertForward(cancel.forward, ACTION_CANCEL, intArrayOf(0, 1), whole = true)
        assertNotEquals(0L, cancel.tokens[0])
        assertFalse(router.active)
        // After a take the page's stream is over already.
        val token = event(ACTION_DOWN, 0, STYLUS).tokens[0]
        router.take(token)
        assertNull(event(ACTION_CANCEL, 0).forward)
    }

    @Test
    fun `an abort forgets the recordings and swallows the rest, but for a stylus`() {
        event(ACTION_DOWN, 0, STYLUS)
        event(ACTION_POINTER_DOWN, 1)
        assertForward(router.abort(), ACTION_CANCEL, intArrayOf(0, 1))
        assertTrue(router.swallowing)
        val moved = move()
        assertNull(moved.forward)
        assertArrayEquals(longArrayOf(0, 0), moved.tokens)
        val pen = event(ACTION_POINTER_DOWN, 2, STYLUS)
        assertForward(pen.forward, ACTION_DOWN, intArrayOf(2), fresh = true)
        assertNotEquals(0L, pen.tokens[2])
        // Nothing down: nothing to end.
        event(ACTION_CANCEL, 0)
        assertNull(router.abort())
        assertFalse(router.swallowing)
    }

    @Test
    fun `another device's events are no gesture's`() {
        event(ACTION_DOWN, 0, STYLUS, device = 1)
        val other = move(device = 2)
        assertFalse(other.accepted)
        assertNull(other.forward)
        assertArrayEquals(longArrayOf(0), other.tokens)
        assertTrue(move(device = 1).accepted)
        event(ACTION_UP, 0)
        val stray = router.route(ACTION_MOVE, 0, intArrayOf(0), intArrayOf(FINGER), 0, false, 1)
        assertFalse(stray.accepted)
        assertNull(stray.forward)
    }

    @Test
    fun `a take for a pointer no longer down changes nothing`() {
        val token = event(ACTION_DOWN, 0, STYLUS).tokens[0]
        event(ACTION_UP, 0)
        assertNull(router.take(token))
        assertFalse(router.swallowing)
    }

    private companion object {
        const val FINGER = 1
        const val STYLUS = TOOL_TYPE_STYLUS
    }
}
