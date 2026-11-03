package com.gammapdf.gamma.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CSS hex colours as colour ints (CssColor), the cases of ipad/GammaIPadTests/ShellTests.swift CSSColorTests. */
class CssColorTest {
    @Test
    fun hexColoursInEveryLength() {
        assertEquals(0xffffffff.toInt(), CssColor.parse("#fff"))
        assertEquals(0xffff0000.toInt(), CssColor.parse("#ff0000"))
        assertEquals("the alpha last, as CSS writes it", 0x80000000.toInt(), CssColor.parse("#00000080"))
        assertEquals(0x801f4fd8.toInt(), CssColor.parse("#1F4FD880"))
        assertEquals(0xffaabbcc.toInt(), CssColor.parse("#abc"))
        assertEquals("spaces around it", 0xff1f4fd8.toInt(), CssColor.parse(" \t#1f4fd8 "))
    }

    @Test
    fun anythingElseIsNoColour() {
        assertNull(CssColor.parse("red"))
        assertNull(CssColor.parse("#12345"))
        assertNull(CssColor.parse("#gggggg"))
        assertNull(CssColor.parse("#+12345"))
        assertNull(CssColor.parse("1f4fd8"))
        assertNull(CssColor.parse("#"))
        assertNull(CssColor.parse(""))
        assertNull(CssColor.parse(null))
    }

    @Test
    fun anOpacityReplacesTheAlpha() {
        val blue = CssColor.parse("#1f4fd880")!!
        assertEquals("an eight-digit colour's alpha is discarded", 0xff1f4fd8.toInt(), CssColor.withAlpha(blue, 1.0))
        assertEquals(0x591f4fd8, CssColor.withAlpha(blue, 0.35))
        assertEquals(0x001f4fd8, CssColor.withAlpha(blue, 0.0))
        assertEquals(0x001f4fd8, CssColor.withAlpha(blue, -1.0))
        assertEquals(0xff1f4fd8.toInt(), CssColor.withAlpha(blue, 2.0))
        assertEquals(0x001f4fd8, CssColor.withAlpha(blue, Double.NaN))
    }
}
