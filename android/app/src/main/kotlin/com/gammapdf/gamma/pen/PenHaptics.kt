package com.gammapdf.gamma.pen

/**
 * The feedback each `haptic` kind plays (docs/dev/android.md "The pen"), the
 * Android counterpart of ipad/GammaIPad/Pencil/PencilHaptics.swift HapticFeel:
 * a HapticFeedbackConstants value for View.performHapticFeedback. The motor is
 * in the tablet, not the pen, so the page's `at` changes nothing here, and each
 * plays under the system's touch-feedback setting. The values are
 * android.view.HapticFeedbackConstants' (android.jar, API 36), written out so
 * the choice is tested on the JVM.
 */
object PenHaptics {
    const val KEYBOARD_TAP = 3
    const val CLOCK_TICK = 4
    const val CONTEXT_CLICK = 6
    /** API 30. */
    const val CONFIRM = 16
    /** API 34. */
    const val TOGGLE_ON = 21
    /** API 34. */
    const val GESTURE_THRESHOLD_ACTIVATE = 23
    /** API 34. */
    const val SEGMENT_TICK = 26

    /**
     * The feedback for `kind` on a device of `sdkInt`: a held stroke's snap to
     * a shape crosses a threshold; a pen lasso's selection ticks; a gesture's
     * tool switch, and a kind the shell does not know, toggles.
     */
    fun constant(kind: String, sdkInt: Int): Int = when (kind) {
        "snap" -> when {
            sdkInt >= 34 -> GESTURE_THRESHOLD_ACTIVATE
            sdkInt >= 30 -> CONFIRM
            else -> CONTEXT_CLICK
        }
        "select" -> if (sdkInt >= 34) SEGMENT_TICK else CLOCK_TICK
        else -> if (sdkInt >= 34) TOGGLE_ON else KEYBOARD_TAP
    }
}
