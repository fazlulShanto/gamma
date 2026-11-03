package com.gammapdf.gamma.pen

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PenLayer's routing with real MotionEvents (docs/dev/android.md "The pen"),
 * which `adb shell input` cannot make: what the page gets of a stylus, a
 * button held at the down, a claim, a palm during the capture, and a button
 * clicked while the pen hovers. The page is a plain View that keeps what it
 * is given; the layer is not in a window, so its live line draws nowhere,
 * which these tests do not look at.
 */
@RunWith(AndroidJUnit4::class)
class PenLayerTest {
    // A context with a display, as the activity's is: the motion predictor reads the view's.
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.let {
        it.createDisplayContext(it.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
    }
    private val density = context.resources.displayMetrics.density.toDouble()

    /** What the page was given: each event's action and its pointers' tool types. */
    private class Page(context: Context) : View(context) {
        val given = mutableListOf<Pair<Int, List<Int>>>()
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            given += event.actionMasked to (0 until event.pointerCount).map { event.getToolType(it) }
            return true
        }
    }

    private class Heard : PenLayer.Listener {
        val strokes = mutableListOf<List<DoubleArray>>()
        val abandoned = mutableListOf<Int>()
        val buttons = mutableListOf<Pair<String, String>>()
        override fun onStroke(pointerId: Int, samples: List<DoubleArray>, held: Int?, line: LiveInk.Handle?) {
            strokes += samples
        }
        override fun onAbandon(pointerId: Int) {
            abandoned += pointerId
        }
        override fun onHold(pointerId: Int, samples: List<DoubleArray>) {}
        override fun onPenButton(gesture: String, action: String) {
            buttons += gesture to action
        }
    }

    private val tool = InkTool(kind = InkKind.PEN, color = "#1f1f1f", size = 2.0)

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun layer(): Triple<PenLayer, Page, Heard> {
        val layer = PenLayer(context)
        val page = Page(context)
        val heard = Heard()
        layer.setPage(page)
        layer.listener = heard
        val size = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        layer.measure(size, size)
        layer.layout(0, 0, 1000, 1000)
        return Triple(layer, page, heard)
    }

    /** One event of pointers (id, tool, x, y), at `time` ms of a gesture that began at `down`. */
    private fun event(
        action: Int, down: Long, time: Long, pointers: List<Pointer>, buttons: Int = 0,
        source: Int = InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN,
    ): MotionEvent {
        val properties = pointers.map { p -> MotionEvent.PointerProperties().also { it.id = p.id; it.toolType = p.tool } }
        val coords = pointers.map { p -> MotionEvent.PointerCoords().also { it.x = p.x; it.y = p.y; it.pressure = 0.5f } }
        return MotionEvent.obtain(down, time, action, pointers.size, properties.toTypedArray(), coords.toTypedArray(),
            0, buttons, 1f, 1f, 0, 0, source, 0)
    }

    private data class Pointer(val id: Int, val tool: Int, val x: Float, val y: Float)

    private fun pen(x: Float, y: Float) = Pointer(0, MotionEvent.TOOL_TYPE_STYLUS, x, y)
    private fun finger(x: Float, y: Float) = Pointer(1, MotionEvent.TOOL_TYPE_FINGER, x, y)

    private fun PenLayer.touch(e: MotionEvent) {
        dispatchTouchEvent(e)
        e.recycle()
    }

    private fun PenLayer.hover(e: MotionEvent) {
        dispatchGenericMotionEvent(e)
        e.recycle()
    }

    @Test
    fun aStylusNobodyClaimsGoesToThePageAsItCame() = onMain {
        val (layer, page, heard) = layer()
        layer.touch(event(MotionEvent.ACTION_DOWN, 0, 0, listOf(pen(100f, 100f))))
        layer.touch(event(MotionEvent.ACTION_MOVE, 0, 16, listOf(pen(140f, 110f))))
        layer.touch(event(MotionEvent.ACTION_UP, 0, 32, listOf(pen(180f, 120f))))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), page.given.map { it.first })
        assertTrue(page.given.all { it.second == listOf(MotionEvent.TOOL_TYPE_STYLUS) })
        assertTrue(heard.strokes.isEmpty())
    }

    @Test
    fun theButtonHeldAtTheDownMakesTheStrokeTheEraserAndUnclaimable() = onMain {
        val (layer, page, _) = layer()
        val primary = MotionEvent.BUTTON_STYLUS_PRIMARY
        layer.touch(event(MotionEvent.ACTION_DOWN, 0, 0, listOf(pen(100f, 100f)), primary))
        layer.touch(event(MotionEvent.ACTION_MOVE, 0, 16, listOf(pen(140f, 110f)), primary))
        assertFalse(layer.claim(1, 100 / density, 100 / density, tool))
        layer.touch(event(MotionEvent.ACTION_MOVE, 0, 32, listOf(pen(180f, 120f)), 0))
        layer.touch(event(MotionEvent.ACTION_UP, 0, 48, listOf(pen(200f, 120f))))
        assertEquals(4, page.given.size)
        assertTrue("the whole stroke is the eraser's", page.given.all { it.second == listOf(MotionEvent.TOOL_TYPE_ERASER) })
    }

    @Test
    fun aClaimTakesTheStrokeAndThePageHearsOneCancel() = onMain {
        val (layer, page, heard) = layer()
        layer.touch(event(MotionEvent.ACTION_DOWN, 0, 0, listOf(pen(100f, 100f))))
        layer.touch(event(MotionEvent.ACTION_MOVE, 0, 16, listOf(pen(140f, 110f))))
        assertTrue(layer.claim(7, 100 / density, 100 / density, tool))
        assertEquals(MotionEvent.ACTION_CANCEL, page.given.last().first)
        val beforePalm = page.given.size
        // A palm lands while the pen writes: the page hears nothing of it.
        layer.touch(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0, 24,
            listOf(pen(150f, 112f), finger(600f, 700f)), source = InputDevice.SOURCE_TOUCHSCREEN))
        layer.touch(event(MotionEvent.ACTION_MOVE, 0, 32, listOf(pen(180f, 120f), finger(605f, 702f))))
        layer.touch(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0, 40,
            listOf(pen(190f, 121f), finger(605f, 702f))))
        layer.touch(event(MotionEvent.ACTION_UP, 0, 48, listOf(pen(200f, 122f))))
        assertEquals(beforePalm, page.given.size)
        assertEquals(1, heard.strokes.size)
        val samples = heard.strokes.single()
        assertTrue("every stylus sample from the down: ${samples.size}", samples.size >= 4)
        assertEquals(100 / density, samples.first()[0], 1e-6)
        assertEquals(100 / density, samples.first()[1], 1e-6)
        assertEquals(200 / density, samples.last()[0], 1e-6)
        assertTrue(heard.abandoned.isEmpty())
    }

    @Test
    fun aClaimAfterTheLiftTakesNothing() = onMain {
        val (layer, page, heard) = layer()
        layer.touch(event(MotionEvent.ACTION_DOWN, 0, 0, listOf(pen(300f, 300f))))
        layer.touch(event(MotionEvent.ACTION_UP, 0, 20, listOf(pen(300f, 300f))))
        assertFalse(layer.claim(3, 300 / density, 300 / density, tool))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), page.given.map { it.first })
        assertTrue(heard.strokes.isEmpty())
        // The next stroke at the same place can still be claimed.
        layer.touch(event(MotionEvent.ACTION_DOWN, 100, 100, listOf(pen(301f, 300f))))
        assertTrue(layer.claim(4, 301 / density, 300 / density, tool))
    }

    @Test
    fun aButtonClickedWhileThePenHoversIsAPenGesture() = onMain {
        val (layer, _, heard) = layer()
        val hover = InputDevice.SOURCE_STYLUS
        val primary = MotionEvent.BUTTON_STYLUS_PRIMARY
        layer.hover(event(MotionEvent.ACTION_HOVER_ENTER, 0, 0, listOf(pen(500f, 500f)), 0, hover))
        layer.hover(event(MotionEvent.ACTION_HOVER_MOVE, 0, 10, listOf(pen(501f, 500f)), 0, hover))
        layer.hover(event(MotionEvent.ACTION_HOVER_MOVE, 0, 20, listOf(pen(502f, 500f)), primary, hover))
        layer.hover(event(MotionEvent.ACTION_HOVER_MOVE, 0, 120, listOf(pen(503f, 500f)), 0, hover))
        assertEquals(listOf("tap" to "switchEraser"), heard.buttons)
        // The second button, the same way: a phase-less squeeze.
        val secondary = MotionEvent.BUTTON_STYLUS_SECONDARY
        layer.hover(event(MotionEvent.ACTION_HOVER_MOVE, 0, 400, listOf(pen(503f, 501f)), secondary, hover))
        layer.hover(event(MotionEvent.ACTION_HOVER_MOVE, 0, 480, listOf(pen(503f, 502f)), 0, hover))
        assertEquals("squeeze" to "switchPrevious", heard.buttons.last())
        assertNotNull(heard.buttons.singleOrNull { it.first == "squeeze" })
    }

    @Test
    fun aButtonPressedWhileWritingIsNoGesture() = onMain {
        val (layer, _, heard) = layer()
        val primary = MotionEvent.BUTTON_STYLUS_PRIMARY
        layer.hover(event(MotionEvent.ACTION_HOVER_MOVE, 0, 0, listOf(pen(500f, 500f)), 0, InputDevice.SOURCE_STYLUS))
        layer.touch(event(MotionEvent.ACTION_DOWN, 10, 10, listOf(pen(500f, 500f)), primary))
        layer.touch(event(MotionEvent.ACTION_UP, 10, 60, listOf(pen(520f, 500f)), primary))
        layer.hover(event(MotionEvent.ACTION_HOVER_MOVE, 0, 80, listOf(pen(520f, 501f)), 0, InputDevice.SOURCE_STYLUS))
        assertTrue(heard.buttons.isEmpty())
    }
}
