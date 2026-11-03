package com.gammapdf.gamma.pen

import com.gammapdf.gamma.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A claim's tool as the web posts it (InkTool.decode) and its live nib: the
 * tool cases of ipad/GammaIPadTests/BridgeTests.swift. Each tool goes through
 * JSON text and org.json, as a posted message does, so a number is whatever
 * org.json makes of it (an Integer, a BigDecimal).
 */
class InkToolTest {
    @Suppress("UNCHECKED_CAST")
    private fun decode(spec: Map<String, Any?>): InkTool = InkTool.decode(Json.parse(Json.write(spec)) as Map<String, Any?>)

    /** The claim's tool: a plain pen with `keys` over it. */
    private fun tool(keys: Map<String, Any?> = emptyMap()): InkTool =
        decode(mapOf("kind" to "pen", "color" to "#1f4fd8", "size" to 3) + keys)

    @Test
    fun aClaimDecodesWithItsTool() {
        assertEquals(
            InkTool(InkKind.HIGHLIGHTER, "#fde047", 18.0, opacity = 0.35),
            decode(mapOf("kind" to "highlighter", "color" to "#fde047", "size" to 18, "opacity" to 0.35)),
        )
    }

    @Test
    fun aToolWithoutOpacityIsOpaque() {
        assertEquals(1.0, decode(mapOf("kind" to "pen", "color" to "#111111", "size" to 2)).opacity, 0.0)
        assertEquals("a null is no opacity", 1.0, tool(mapOf("opacity" to null)).opacity, 0.0)
    }

    @Test
    fun aToolWithoutItsLookDrawsThePlainLine() {
        val plain = tool()
        assertEquals(Brush.PEN, plain.brush)
        assertTrue(plain.pressure)
        assertNull(plain.dash)
        assertFalse(plain.multiply)
        assertEquals(0, plain.stabilize)
        assertNull(plain.snap)
        assertEquals(45, plain.nib)
        assertFalse(plain.nibRoll)
    }

    @Test
    fun aClaimCarriesThePresetsLook() {
        // the claim hostInk.test.mjs makes for a dashed Brush preset, plus a blend and a snap
        val look = tool(mapOf(
            "opacity" to 1, "brush" to "brush", "pressure" to false, "dash" to listOf(6, 6), "blend" to "multiply",
            "stabilize" to 2, "snap" to mapOf("x0" to 10, "y0" to 20, "angle" to 15),
        ))
        assertEquals(
            InkTool(
                InkKind.PEN, "#1f4fd8", 3.0, opacity = 1.0, brush = Brush.BRUSH, pressure = false, dash = listOf(6.0, 6.0),
                multiply = true, stabilize = 2, snap = Snap(10.0, 20.0, 15.0),
            ),
            look,
        )
        assertEquals(Brush.MONOLINE, tool(mapOf("brush" to "monoline")).brush)
        assertEquals(Brush.PENCIL, tool(mapOf("brush" to "pencil")).brush)
        assertEquals(Brush.PEN, tool(mapOf("brush" to "pen")).brush)
        assertEquals(Brush.CALLIGRAPHY, tool(mapOf("brush" to "calligraphy")).brush)
        assertEquals("dotted: a short on, a long off", listOf(0.3, 6.0), tool(mapOf("dash" to listOf(0.3, 6))).dash)
    }

    @Test
    fun aLookTheShellCannotReadIsDroppedNotTheClaim() {
        assertEquals("a kind but the highlighter draws as a pen", InkKind.PEN, tool(mapOf("kind" to "eraser")).kind)
        assertEquals(InkKind.PEN, tool(mapOf("kind" to "calligraphy")).kind)
        assertEquals("an unknown brush is the plain Pen", Brush.PEN, tool(mapOf("brush" to "charcoal")).brush)
        assertEquals(Brush.PEN, tool(mapOf("brush" to 7)).brush)
        assertEquals("names are as the web writes them", Brush.PEN, tool(mapOf("brush" to "Brush")).brush)
        assertTrue(tool(mapOf("pressure" to "no")).pressure)
        assertNull("a dash is two lengths", tool(mapOf("dash" to listOf(6))).dash)
        assertNull(tool(mapOf("dash" to listOf(6, 6, 6))).dash)
        assertNull("both above 0", tool(mapOf("dash" to listOf(6, 0))).dash)
        assertNull(tool(mapOf("dash" to listOf(-6, 6))).dash)
        assertNull(tool(mapOf("dash" to "dashed")).dash)
        assertNull(tool(mapOf("dash" to listOf(6, "6"))).dash)
        assertNull(tool(mapOf("dash" to listOf(6, null))).dash)
        assertFalse(tool(mapOf("blend" to "screen")).multiply)
        assertFalse(tool(mapOf("blend" to true)).multiply)
        assertEquals("held to the levels there are", 4, tool(mapOf("stabilize" to 9)).stabilize)
        assertEquals(0, tool(mapOf("stabilize" to -2)).stabilize)
        assertEquals("rounded", 3, tool(mapOf("stabilize" to 2.6)).stabilize)
        assertEquals("halves away from zero", 3, tool(mapOf("stabilize" to 2.5)).stabilize)
        assertEquals(0, tool(mapOf("stabilize" to "max")).stabilize)
        assertEquals(0, tool(mapOf("stabilize" to true)).stabilize)
        assertNull(tool(mapOf("snap" to mapOf("x0" to 1, "y0" to "top", "angle" to 0))).snap)
        assertNull(tool(mapOf("snap" to mapOf("x0" to 1, "y0" to 2))).snap)
        assertNull(tool(mapOf("snap" to 5)).snap)
        assertEquals(
            "keys the shell does not know are ignored", tool(),
            tool(mapOf("grain" to mapOf("angle" to 30), "thinning" to 0.5)),
        )
        assertEquals("a nib that is no number is the default", 45, tool(mapOf("nib" to mapOf("angle" to 30))).nib)
        assertEquals(45, tool(mapOf("nib" to "steep")).nib)
        assertEquals("a nib beyond reason is the default", 45, tool(mapOf("nib" to 1e7)).nib)
        assertFalse(tool(mapOf("nibRoll" to "yes")).nibRoll)
    }

    @Test
    fun aToolWithoutWhatItDrawsIsRefused() {
        assertThrows("a tool without its kind", IllegalArgumentException::class.java) { decode(mapOf("color" to "#000", "size" to 1)) }
        assertThrows("a tool without its size", IllegalArgumentException::class.java) { decode(mapOf("kind" to "pen", "color" to "#000")) }
        assertThrows("a tool without its colour", IllegalArgumentException::class.java) { decode(mapOf("kind" to "pen", "size" to 1)) }
        assertThrows("a kind that is no string", IllegalArgumentException::class.java) { tool(mapOf("kind" to 1)) }
        assertThrows("a size that is no number", IllegalArgumentException::class.java) { tool(mapOf("size" to "3")) }
        assertThrows("an opacity that is no number is not lenient", IllegalArgumentException::class.java) {
            tool(mapOf("opacity" to "0.5"))
        }
    }

    @Test
    fun aCalligraphyPensClaimCarriesItsNib() {
        val cal = tool(mapOf("brush" to "calligraphy", "nib" to 150, "nibRoll" to true))
        assertEquals(InkTool(InkKind.PEN, "#1f4fd8", 3.0, brush = Brush.CALLIGRAPHY, nib = 150, nibRoll = true), cal)
        val plain = tool(mapOf("brush" to "calligraphy"))
        assertEquals("a claim without a nib: 45", 45, plain.nib)
        assertFalse("and no roll", plain.nibRoll)
        assertEquals("turned into 0…179", 20, tool(mapOf("nib" to 200.4)).nib)
        assertEquals(150, tool(mapOf("nib" to -30)).nib)
        assertEquals("rounded half away from zero", 0, tool(mapOf("nib" to 179.5)).nib)
        assertEquals(179, tool(mapOf("nib" to -0.5)).nib)
        // the live nib: the claim's, less the first sample's clockwise roll, mod 180
        assertEquals(120, cal.liveNib(30.0))
        assertEquals(130, cal.liveNib(200.0))
        assertEquals("a sample without a roll keeps the nib", 150, cal.liveNib(null))
        assertEquals(150, cal.liveNib(Double.NaN))
        assertEquals("Lock angle: no roll", 150, tool(mapOf("brush" to "calligraphy", "nib" to 150)).liveNib(30.0))
    }
}
