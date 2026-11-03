package com.gammapdf.gamma.pen

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The feedback each `haptic` kind plays (PenHaptics), the Android counterpart
 * of ipad/GammaIPadTests/PencilTests.swift HapticFeelTests: each kind its own,
 * on every API level the app runs on, and an unknown kind a tool's tick.
 */
class PenHapticsTest {
    @Test
    fun eachKindPlaysAFeedbackOfItsOwn() {
        for (sdk in listOf(29, 30, 33, 34, 36)) {
            val feels = listOf("snap", "select", "tool").map { PenHaptics.constant(it, sdk) }
            assertEquals("API $sdk: $feels", 3, feels.toSet().size)
        }
    }

    @Test
    fun theNewestFeedbackWhereTheSystemHasIt() {
        assertEquals("a held shape crosses a threshold", HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, PenHaptics.constant("snap", 34))
        assertEquals("a pen lasso's selection ticks", HapticFeedbackConstants.SEGMENT_TICK, PenHaptics.constant("select", 34))
        assertEquals("a gesture's tool switch toggles", HapticFeedbackConstants.TOGGLE_ON, PenHaptics.constant("tool", 36))
        assertEquals(HapticFeedbackConstants.CONFIRM, PenHaptics.constant("snap", 30))
        assertEquals(HapticFeedbackConstants.CONFIRM, PenHaptics.constant("snap", 33))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, PenHaptics.constant("snap", 29))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, PenHaptics.constant("select", 33))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, PenHaptics.constant("tool", 33))
    }

    @Test
    fun anUnknownKindIsAToolsTick() {
        assertEquals(PenHaptics.constant("tool", 34), PenHaptics.constant("buzz", 34))
        assertEquals(PenHaptics.constant("tool", 29), PenHaptics.constant("", 29))
    }

    @Test
    fun theValuesAreAndroids() {
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, PenHaptics.KEYBOARD_TAP)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, PenHaptics.CLOCK_TICK)
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, PenHaptics.CONTEXT_CLICK)
        assertEquals(HapticFeedbackConstants.CONFIRM, PenHaptics.CONFIRM)
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, PenHaptics.TOGGLE_ON)
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, PenHaptics.GESTURE_THRESHOLD_ACTIVATE)
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, PenHaptics.SEGMENT_TICK)
        assertEquals(26, PenHaptics.SEGMENT_TICK)
    }
}
