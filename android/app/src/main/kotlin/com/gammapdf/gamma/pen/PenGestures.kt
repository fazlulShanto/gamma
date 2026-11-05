package com.gammapdf.gamma.pen

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * A stylus's buttons as the host's pen gestures (docs/dev/android.md "The
 * pen"), the Android counterpart of ipad/GammaIPad/Pencil/PencilGestures.swift:
 * PenButtons, fed by PenLayer with every stylus MotionEvent (from the touch
 * stream in contact, from the generic stream while it hovers, which carries
 * ACTION_BUTTON_PRESS and RELEASE) and by MainActivity with every stylus
 * button key, says which are clicks, and each goes to `onClick` with the
 * `pencil` message's gesture and action. Nothing is taken from the events:
 * the page still gets every one, and its hover cursor reads them.
 */
class PenGestures(private val onClick: (gesture: String, action: String) -> Unit) {
    private val buttons = PenButtons()

    /** A MotionEvent of the touch stream (`inContact`) or the generic one; any but a stylus's changes nothing. */
    fun motion(event: MotionEvent, inContact: Boolean) {
        if (!isStylus(event)) return
        for (click in buttons.motion(event.actionMasked, event.buttonState, inContact, event.eventTime / 1000.0)) {
            onClick(click.gesture, click.action)
        }
    }

    /**
     * A key event, before any view has it: true when it is a stylus button's
     * (KEYCODE_STYLUS_BUTTON_PRIMARY or SECONDARY), which the shell takes; the
     * tail button stays the system's, which opens a notes app.
     */
    fun key(event: KeyEvent): Boolean {
        if (!PenButtons.isButtonKey(event.keyCode)) return false
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            buttons.key(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.eventTime / 1000.0)
                ?.let { onClick(it.gesture, it.action) }
        }
        return true
    }

    /** Every press forgotten, as when the window loses focus: a release that comes later clicks nothing. */
    fun reset() = buttons.reset()

    companion object {
        /** Whether a pointer of the event is a stylus's, its tip or its eraser. */
        fun isStylus(event: MotionEvent): Boolean =
            (0 until event.pointerCount).any { PenRouter.isStylus(event.getToolType(it)) }
    }
}
