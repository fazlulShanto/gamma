package com.gammapdf.gamma.shell

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell's shortcuts and the system's back (shell/ShellKeys.kt). */
class ShellKeysTest {
    @Test
    fun theKeyCodesAreAndroids() {
        assertEquals(KeyEvent.KEYCODE_L, Shortcut.ALL_SERVERS.keyCode)
        assertEquals(KeyEvent.KEYCODE_R, Shortcut.RELOAD.keyCode)
        assertEquals(KeyEvent.KEYCODE_B, Shortcut.TOGGLE_BAR.keyCode)
    }

    @Test
    fun theShortcutsAreExactlyTheBarsKeys() {
        fun of(code: Int, ctrl: Boolean = true, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false) =
            Shortcut.of(code, ctrl, shift, alt, meta)
        assertEquals(Shortcut.ALL_SERVERS, of(KeyEvent.KEYCODE_L, shift = true))
        assertEquals(Shortcut.RELOAD, of(KeyEvent.KEYCODE_R))
        assertEquals(Shortcut.TOGGLE_BAR, of(KeyEvent.KEYCODE_B, shift = true))
        assertNull("Ctrl+L is the page's", of(KeyEvent.KEYCODE_L))
        assertNull("Ctrl+B is the page's (bold)", of(KeyEvent.KEYCODE_B))
        assertNull("Ctrl+Shift+R is no reload", of(KeyEvent.KEYCODE_R, shift = true))
        assertNull(of(KeyEvent.KEYCODE_R, ctrl = false))
        assertNull(of(KeyEvent.KEYCODE_R, alt = true))
        assertNull(of(KeyEvent.KEYCODE_L, shift = true, meta = true))
        assertNull(of(KeyEvent.KEYCODE_A, shift = true))
    }

    @Test
    fun backDoesTheFirstThingThatApplies() {
        assertEquals(BackStep.CLOSE_MENU, BackStep.of(menuOpen = true, launcherUp = true, launcherCloses = true, canGoBack = true))
        assertEquals(BackStep.CLOSE_LAUNCHER, BackStep.of(menuOpen = false, launcherUp = true, launcherCloses = true, canGoBack = true))
        assertEquals("a failure's launcher, or one over no server, stays",
                     BackStep.LEAVE, BackStep.of(menuOpen = false, launcherUp = true, launcherCloses = false, canGoBack = true))
        assertEquals(BackStep.PAGE_BACK, BackStep.of(menuOpen = false, launcherUp = false, launcherCloses = false, canGoBack = true))
        assertEquals(BackStep.LEAVE, BackStep.of(menuOpen = false, launcherUp = false, launcherCloses = false, canGoBack = false))
    }

    @Test
    fun backNeverLeavesTheServerShown() {
        val local = "http://127.0.0.1:50123"
        assertTrue(BackStep.staysOnServer("http://127.0.0.1:50123/?ws=w1", local))
        assertTrue("the default port is the same origin", BackStep.staysOnServer("https://gamma.example.org:443/p/1", "https://gamma.example.org"))
        assertFalse("a server shown before the switch", BackStep.staysOnServer("https://gamma.example.org/", local))
        assertFalse("the local server before it restarted on another port", BackStep.staysOnServer("http://127.0.0.1:50999/", local))
        assertFalse("no entry before", BackStep.staysOnServer(null, local))
        assertFalse(BackStep.staysOnServer("about:blank", local))
        assertFalse("no server shown", BackStep.staysOnServer("http://127.0.0.1:50123/", null))
    }
}
