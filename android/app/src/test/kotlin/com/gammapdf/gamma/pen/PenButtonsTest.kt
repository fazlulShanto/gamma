package com.gammapdf.gamma.pen

import android.view.KeyEvent
import android.view.MotionEvent
import com.gammapdf.gamma.pen.PenButtons.Click
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stylus buttons' clicks (PenButtons), fed as the pen layer feeds it, with the times given in seconds. */
class PenButtonsTest {
    private val primary = MotionEvent.BUTTON_STYLUS_PRIMARY
    private val secondary = MotionEvent.BUTTON_STYLUS_SECONDARY

    private fun PenButtons.hover(buttons: Int, time: Double, action: Int = MotionEvent.ACTION_HOVER_MOVE) =
        motion(action, buttons, inContact = false, time = time)

    private fun PenButtons.touch(action: Int, buttons: Int, time: Double) = motion(action, buttons, inContact = true, time = time)

    /** A press and release while hovering, as Android reports it: a BUTTON_PRESS, hover moves, a BUTTON_RELEASE. */
    private fun PenButtons.hoverClick(button: Int, at: Double): List<Click> {
        val heard = hover(button, at, MotionEvent.ACTION_BUTTON_PRESS) + hover(button, at + 0.05) +
            hover(0, at + 0.1, MotionEvent.ACTION_BUTTON_RELEASE) + hover(0, at + 0.12)
        return heard
    }

    @Test
    fun aClickWhileHoveringIsTheButtonsGesture() {
        val buttons = PenButtons()
        assertEquals(emptyList<Click>(), buttons.hover(0, 0.0, MotionEvent.ACTION_HOVER_ENTER))
        assertEquals(emptyList<Click>(), buttons.hover(primary, 0.1, MotionEvent.ACTION_BUTTON_PRESS))
        assertEquals("nothing until the release", emptyList<Click>(), buttons.hover(primary, 0.15))
        assertEquals(listOf(Click.PRIMARY), buttons.hover(0, 0.2, MotionEvent.ACTION_BUTTON_RELEASE))
        assertEquals("one click", emptyList<Click>(), buttons.hover(0, 0.21))
        assertEquals(listOf(Click.SECONDARY), buttons.hoverClick(secondary, 1.0))
        assertEquals("the edges are enough: no BUTTON_PRESS needed", listOf(Click.PRIMARY), buttons.hover(primary, 2.0) + buttons.hover(0, 2.1))
    }

    @Test
    fun theClicksAreTheHostsPencilGestures() {
        assertEquals("tap", Click.PRIMARY.gesture)
        assertEquals("switchEraser", Click.PRIMARY.action)
        assertEquals("squeeze", Click.SECONDARY.gesture)
        assertEquals("switchPrevious", Click.SECONDARY.action)
    }

    @Test
    fun aPressThePenWritesDuringIsNoClick() {
        val buttons = PenButtons()
        buttons.hover(primary, 0.0, MotionEvent.ACTION_BUTTON_PRESS)
        buttons.hover(primary, 0.05, MotionEvent.ACTION_HOVER_EXIT)
        buttons.touch(MotionEvent.ACTION_DOWN, primary, 0.06)
        buttons.touch(MotionEvent.ACTION_MOVE, primary, 0.1)
        buttons.touch(MotionEvent.ACTION_UP, primary, 0.2)
        buttons.hover(primary, 0.21, MotionEvent.ACTION_HOVER_ENTER)
        assertEquals("pressed, wrote, released while hovering", emptyList<Click>(), buttons.hover(0, 0.3, MotionEvent.ACTION_BUTTON_RELEASE))
        assertEquals("the next click counts", listOf(Click.PRIMARY), buttons.hoverClick(primary, 1.0))
    }

    @Test
    fun aButtonHeldWhileWritingIsTheBarrelNotAClick() {
        val buttons = PenButtons()
        buttons.touch(MotionEvent.ACTION_DOWN, primary, 0.0)
        buttons.touch(MotionEvent.ACTION_MOVE, primary, 0.05)
        assertEquals(emptyList<Click>(), buttons.touch(MotionEvent.ACTION_UP, primary, 0.1))
        buttons.hover(primary, 0.11, MotionEvent.ACTION_HOVER_ENTER)
        assertEquals("released after the lift", emptyList<Click>(), buttons.hover(0, 0.2, MotionEvent.ACTION_BUTTON_RELEASE))
        // a digitizer that drops the buttons at the lift and shows them again in hover
        buttons.touch(MotionEvent.ACTION_DOWN, primary, 1.0)
        buttons.touch(MotionEvent.ACTION_UP, 0, 1.1)
        buttons.hover(primary, 1.11, MotionEvent.ACTION_HOVER_ENTER)
        buttons.hover(primary, 1.15)
        assertEquals("the same press, not a new one", emptyList<Click>(), buttons.hover(0, 1.2, MotionEvent.ACTION_BUTTON_RELEASE))
        assertEquals(listOf(Click.PRIMARY), buttons.hoverClick(primary, 2.0))
    }

    @Test
    fun aButtonPressedWhileTouchingIsNoClick() {
        // BUTTON_PRESS and RELEASE come as generic events, the touch stream's contact still holding
        val buttons = PenButtons()
        buttons.touch(MotionEvent.ACTION_DOWN, 0, 0.0)
        buttons.hover(primary, 0.05, MotionEvent.ACTION_BUTTON_PRESS)
        assertEquals("the pen rests: no move between", emptyList<Click>(), buttons.hover(0, 0.1, MotionEvent.ACTION_BUTTON_RELEASE))
        buttons.touch(MotionEvent.ACTION_UP, 0, 0.2)
        // the button seen up while hovering: the next press is one
        buttons.hover(0, 0.21, MotionEvent.ACTION_HOVER_ENTER)
        assertEquals("after the lift, a click again", listOf(Click.PRIMARY), buttons.hoverClick(primary, 0.3))
    }

    @Test
    fun leavingRangeWhilePressedIsNoClick() {
        val buttons = PenButtons()
        buttons.hover(primary, 0.0, MotionEvent.ACTION_BUTTON_PRESS)
        assertEquals(emptyList<Click>(), buttons.hover(primary, 0.1, MotionEvent.ACTION_HOVER_EXIT))
        buttons.hover(primary, 2.0, MotionEvent.ACTION_HOVER_ENTER)
        assertEquals("back in range, still held, then released", emptyList<Click>(), buttons.hover(0, 2.1, MotionEvent.ACTION_BUTTON_RELEASE))
        // the exit reports the buttons up while the button is still held
        buttons.hover(secondary, 3.0, MotionEvent.ACTION_BUTTON_PRESS)
        assertEquals(emptyList<Click>(), buttons.hover(0, 3.1, MotionEvent.ACTION_HOVER_EXIT))
        buttons.hover(secondary, 4.0, MotionEvent.ACTION_HOVER_ENTER)
        assertEquals(emptyList<Click>(), buttons.hover(0, 4.1, MotionEvent.ACTION_BUTTON_RELEASE))
        // released out of range, back without it
        buttons.hover(primary, 5.0, MotionEvent.ACTION_BUTTON_PRESS)
        buttons.hover(primary, 5.1, MotionEvent.ACTION_HOVER_EXIT)
        assertEquals(emptyList<Click>(), buttons.hover(0, 6.0, MotionEvent.ACTION_HOVER_ENTER))
        assertEquals("then a click is a click", listOf(Click.SECONDARY), buttons.hoverClick(secondary, 7.0))
        assertEquals(listOf(Click.PRIMARY), buttons.hoverClick(primary, 8.0))
    }

    @Test
    fun aCancelSpoilsThePress() {
        val buttons = PenButtons()
        buttons.hover(primary, 0.0, MotionEvent.ACTION_BUTTON_PRESS)
        buttons.motion(MotionEvent.ACTION_CANCEL, primary, inContact = false, time = 0.05)
        assertEquals(emptyList<Click>(), buttons.hover(0, 0.1, MotionEvent.ACTION_BUTTON_RELEASE))
    }

    @Test
    fun bothButtonsReleasedTogetherAreTwoClicks() {
        val buttons = PenButtons()
        buttons.hover(primary or secondary, 0.0)
        assertEquals(listOf(Click.PRIMARY, Click.SECONDARY), buttons.hover(0, 0.1))
        assertEquals("other buttons are no stylus button", emptyList<Click>(), buttons.hover(MotionEvent.BUTTON_SECONDARY, 1.0) + buttons.hover(0, 1.1))
    }

    @Test
    fun theKeysClickByTheSameRules() {
        val buttons = PenButtons()
        assertNull(buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true, time = 0.0))
        assertNull("a repeat changes nothing", buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true, time = 0.05))
        assertEquals(Click.PRIMARY, buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = false, time = 0.1))
        assertNull("an up without its down", buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = false, time = 0.2))
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, down = true, time = 1.0)
        assertEquals(Click.SECONDARY, buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, down = false, time = 1.1))
        for (other in listOf(KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY, KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL, KeyEvent.KEYCODE_A)) {
            assertNull(buttons.key(other, down = true, time = 2.0))
            assertNull(buttons.key(other, down = false, time = 2.1))
            assertFalse(PenButtons.isButtonKey(other))
        }
        assertTrue(PenButtons.isButtonKey(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY))
        assertTrue(PenButtons.isButtonKey(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY))
    }

    @Test
    fun aKeyPressThePenTouchesDuringIsNoClick() {
        val buttons = PenButtons()
        buttons.touch(MotionEvent.ACTION_DOWN, 0, 0.0)
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true, time = 0.05)
        buttons.touch(MotionEvent.ACTION_UP, 0, 0.1)
        assertNull("pressed while touching", buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = false, time = 0.2))
        buttons.hover(0, 0.3)
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true, time = 1.0)
        buttons.touch(MotionEvent.ACTION_DOWN, 0, 1.05)
        buttons.touch(MotionEvent.ACTION_UP, 0, 1.1)
        assertNull("touched while pressed", buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = false, time = 1.2))
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true, time = 2.0)
        buttons.hover(0, 2.05, MotionEvent.ACTION_HOVER_EXIT)
        assertNull("left range while pressed", buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = false, time = 2.1))
        buttons.hover(0, 3.0, MotionEvent.ACTION_HOVER_ENTER)
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true, time = 3.1)
        assertEquals(Click.PRIMARY, buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = false, time = 3.2))
    }

    @Test
    fun aClickReportedBothWaysIsOneClick() {
        val buttons = PenButtons()
        assertEquals(listOf(Click.PRIMARY), buttons.hover(primary, 0.0) + buttons.hover(0, 0.1))
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true, time = 0.0)
        assertNull("the key's report of it", buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = false, time = 0.11))
        // the key first, then the motion events
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, down = true, time = 1.0)
        assertEquals(Click.SECONDARY, buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, down = false, time = 1.1))
        assertEquals(emptyList<Click>(), buttons.hover(secondary, 1.0) + buttons.hover(0, 1.12))
        // two quick clicks, each reported both ways, are two
        var heard = 0
        for (at in listOf(2.0, 2.25)) {
            heard += (buttons.hover(primary, at) + buttons.hover(0, at + 0.1)).size
            buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true, time = at)
            if (buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = false, time = at + 0.11) != null) heard++
        }
        assertEquals(2, heard)
        // a key click well after a motion click is its own
        assertEquals(listOf(Click.PRIMARY), buttons.hover(primary, 3.0) + buttons.hover(0, 3.1))
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true, time = 3.5)
        assertEquals(Click.PRIMARY, buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = false, time = 3.6))
        // and one button's click is not the other's
        assertEquals(listOf(Click.PRIMARY), buttons.hover(primary, 4.0) + buttons.hover(0, 4.1))
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, down = true, time = 4.0)
        assertEquals(Click.SECONDARY, buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, down = false, time = 4.11))
    }

    @Test
    fun aResetForgetsThePress() {
        val buttons = PenButtons()
        buttons.hover(primary, 0.0)
        buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, down = true, time = 0.0)
        buttons.reset()
        assertEquals(emptyList<Click>(), buttons.hover(0, 0.1))
        assertNull(buttons.key(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, down = false, time = 0.1))
        assertEquals(listOf(Click.PRIMARY), buttons.hoverClick(primary, 1.0))
    }

    @Test
    fun theValuesAreAndroids() {
        assertEquals(MotionEvent.ACTION_UP, PenButtons.ACTION_UP)
        assertEquals(MotionEvent.ACTION_CANCEL, PenButtons.ACTION_CANCEL)
        assertEquals(MotionEvent.ACTION_HOVER_EXIT, PenButtons.ACTION_HOVER_EXIT)
        assertEquals(MotionEvent.ACTION_BUTTON_PRESS, PenButtons.ACTION_BUTTON_PRESS)
        assertEquals(MotionEvent.ACTION_BUTTON_RELEASE, PenButtons.ACTION_BUTTON_RELEASE)
        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, PenButtons.BUTTON_STYLUS_PRIMARY)
        assertEquals(MotionEvent.BUTTON_STYLUS_SECONDARY, PenButtons.BUTTON_STYLUS_SECONDARY)
        assertEquals(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, PenButtons.KEYCODE_STYLUS_BUTTON_PRIMARY)
        assertEquals(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, PenButtons.KEYCODE_STYLUS_BUTTON_SECONDARY)
    }
}
