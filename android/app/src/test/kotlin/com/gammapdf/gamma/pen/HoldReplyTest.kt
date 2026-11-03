package com.gammapdf.gamma.pen

import com.gammapdf.gamma.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The page's answers to `hold` (HoldReply.from), the cases of ipad/GammaIPadTests/BridgeTests.swift testTheHoldsRepliesAreRead. */
class HoldReplyTest {
    /** A reply as the page's promise resolves it, through JSON text and org.json. */
    private fun read(reply: Any?): HoldReply = HoldReply.from(Json.parse(Json.write(reply)))

    @Test
    fun theHoldsRepliesAreRead() {
        val params = mapOf("x1" to 10.0, "y1" to 20.0, "x2" to 210.5, "y2" to 20.0)
        val line = mapOf("kind" to "line", "params" to params)
        val snapped = HoldReply.Shape(HeldShape("line", params))
        assertEquals(snapped, read(mapOf("shape" to line)))
        assertEquals(HoldReply.Cancel, read(mapOf("cancel" to true)))
        assertEquals("a cancel wins", HoldReply.Cancel, read(mapOf("cancel" to true, "shape" to line)))
        assertEquals(snapped, read(mapOf("cancel" to false, "shape" to line)))
        assertEquals(HoldReply.Nothing, read(emptyMap<String, Any?>()))
        assertEquals("not delivered", HoldReply.Nothing, HoldReply.from(null))
        assertEquals(HoldReply.Nothing, read("shape"))
        assertEquals("a cancel that is no Bool", HoldReply.Nothing, read(mapOf("cancel" to "yes")))
        val shapes = listOf(
            Triple("star", mapOf("cx" to 0, "cy" to 0, "r" to 4), "a kind the shell cannot draw"),
            Triple("curve", mapOf("x1" to 0, "y1" to 0, "x2" to 4, "y2" to 4), "nor a curve"),
            Triple("line", mapOf("x1" to 10, "y1" to 20), "params it cannot be drawn from"),
            Triple("circle", mapOf("cx" to 0, "cy" to "middle", "r" to 4), "a param that is not a number"),
            Triple("polygon", mapOf("cx" to 0, "cy" to 0, "r" to 4, "n" to 2), "a polygon has three sides at least"),
        )
        for ((kind, values, why) in shapes) {
            assertEquals(why, HoldReply.Nothing, read(mapOf("shape" to mapOf("kind" to kind, "params" to values))))
        }
        assertEquals(
            "an angle left out reads as 0, as shapes.js reads it",
            HoldReply.Shape(HeldShape("rect", mapOf("cx" to 50.0, "cy" to 60.0, "w" to 40.0, "h" to 20.0))),
            read(mapOf("shape" to mapOf("kind" to "rect", "params" to mapOf("cx" to 50, "cy" to 60, "w" to 40, "h" to 20)))),
        )
    }

    @Test
    fun aShapeComesWithItsParamsAsNumbers() {
        // org.json reads 210.5 as a BigDecimal and 10 as an Integer: both are Doubles here
        val reply = read(mapOf("shape" to mapOf("kind" to "ellipse", "params" to mapOf("cx" to 10, "cy" to 20.25, "rx" to 30, "ry" to 10, "angle" to 170))))
        assertEquals(HoldReply.Shape(HeldShape("ellipse", mapOf("cx" to 10.0, "cy" to 20.25, "rx" to 30.0, "ry" to 10.0, "angle" to 170.0))), reply)
    }
}
