package com.gammapdf.gamma.pen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * LiveLine: the live line frame by frame is the iPad's (LiveStrokeView.show)
 * made whole at every frame, from the same pen core.
 */
class LiveLineTest {
    /** A wandering stroke of `count` samples, some steps under half a CSS px and some none, as a digitizer gives them. */
    private fun stroke(random: Random, count: Int, pressure: Boolean = true): List<DoubleArray> {
        val recorder = StrokeRecorder()
        var x = 100.0
        var y = 100.0
        var heading = random.nextDouble() * 6.28
        for (i in 0 until count) {
            heading += (random.nextDouble() - 0.5) * 0.8
            val step = when (random.nextInt(5)) {
                0 -> 0.0
                1 -> random.nextDouble() * 0.6
                else -> random.nextDouble() * 4
            }
            x += step * kotlin.math.cos(heading)
            y += step * kotlin.math.sin(heading)
            recorder.add(x, y, random.nextDouble(), if (pressure) 1.0 else 0.0, 10 + 0.004 * i)
        }
        return recorder.samples
    }

    /** The iPad's ribbon for these samples and predicted points, made whole (LiveStrokeView.show). */
    private fun ribbon(tool: InkTool, samples: List<DoubleArray>, predicted: List<DoubleArray>): List<Ribbon.Piece> {
        val drawn = LazyBrush.smooth(samples, tool.stabilize)
        val widths = InkWidth.widths(drawn, tool.kind, tool.brush, tool.pressure, tool.size, live = true, nib = tool.liveNib(null))!!
        val points = drawn + if (tool.stabilize > 0) emptyList() else predicted
        val radii = DoubleArray(points.size) { max(tool.size * widths[min(it, widths.size - 1)], 0.5) / 2 }
        val (kept, keptRadii) = Ribbon.thin(points, radii)
        return Ribbon.pieces(kept, keptRadii)
    }

    private fun counts(pieces: List<Ribbon.Piece>): Map<Ribbon.Piece, Int> = pieces.groupingBy { it }.eachCount()

    private fun predicted(random: Random, last: DoubleArray): List<DoubleArray> =
        List(random.nextInt(3)) { doubleArrayOf(last[0] + random.nextDouble() * 3, last[1] + random.nextDouble() * 3) }

    @Test
    fun `the ribbon at every frame is the whole stroke's`() {
        val random = Random(7)
        val tools = listOf(
            InkTool(InkKind.PEN, "#1f4fd8", 3.0),
            InkTool(InkKind.PEN, "#000000", 2.0, brush = Brush.BRUSH),
            InkTool(InkKind.PEN, "#000000", 2.0, brush = Brush.BRUSH, pressure = false),
            InkTool(InkKind.PEN, "#000000", 1.6, brush = Brush.PENCIL),
            InkTool(InkKind.PEN, "#000000", 4.0, brush = Brush.CALLIGRAPHY, nib = 150, stabilize = 2),
            InkTool(InkKind.PEN, "#000000", 0.2, brush = Brush.CALLIGRAPHY, pressure = false),
            InkTool(InkKind.PEN, "#000000", 3.0, stabilize = 3),
        )
        for (tool in tools) {
            repeat(5) {
                val samples = stroke(random, 2 + random.nextInt(120), pressure = random.nextBoolean())
                val line = LiveLine(tool)
                var shown = 0
                var settled = emptyList<Ribbon.Piece>()
                while (shown < samples.size) {
                    shown = min(samples.size, shown + 1 + random.nextInt(6))
                    val so = samples.subList(0, shown)
                    val ahead = predicted(random, so.last())
                    val look = line.frame(so, ahead, null) as LiveLine.Fill
                    assertEquals("$tool at $shown", counts(ribbon(tool, so, ahead)), counts(look.settled + look.tail))
                    // The colour, a Pencil's at the band of its whole mean opacity so far.
                    val shade = InkWidth.opacities(LazyBrush.smooth(so, tool.stabilize), tool.kind, tool.brush, tool.pressure)
                        ?.let { InkWidth.opacityBand(it.average()) } ?: 1.0
                    assertEquals("$tool at $shown", CssColor.withAlpha(CssColor.parse(tool.color)!!, tool.opacity * shade), look.argb)
                    // The settled part only grows.
                    assertEquals(settled, look.settled.subList(0, settled.size))
                    settled = look.settled.toList()
                }
            }
        }
    }

    /**
     * Samples of a wandering stroke, `[x, y, p, t]`, with the pose
     * (`[x, y, p, t, a, z]`) or without the pressure (`[x, y]`), by `form`,
     * the pressure between `light` and `heavy`.
     */
    private fun raw(random: Random, count: Int, form: Int, light: Double, heavy: Double): List<DoubleArray> {
        var x = 100.0
        var y = 100.0
        var heading = random.nextDouble() * 6.28
        return List(count) { i ->
            heading += (random.nextDouble() - 0.5) * 0.8
            val step = when (random.nextInt(5)) {
                0 -> 0.0
                1 -> random.nextDouble() * 0.6
                else -> random.nextDouble() * 4
            }
            x += step * kotlin.math.cos(heading)
            y += step * kotlin.math.sin(heading)
            val p = light + random.nextDouble() * (heavy - light)
            when (form) {
                0 -> doubleArrayOf(x, y, p, 4.0 * i)
                1 -> doubleArrayOf(x, y, p, 4.0 * i, random.nextDouble() * 90, random.nextDouble() * 360)
                else -> doubleArrayOf(x, y)
            }
        }
    }

    @Test
    fun `the widths and the shade kept as the points come are the whole stroke's at every frame`() {
        val random = Random(23)
        val bands = listOf(0.0 to 0.3, 0.2 to 0.8, 0.6 to 1.0)
        var ribbons = 0
        var tapered = 0
        for (brush in Brush.entries) for (pressure in listOf(true, false)) for (size in listOf(0.005, 2.0, 12.0, 40.0)) {
            val tool = InkTool(
                InkKind.PEN, "#000000", size, brush = brush, pressure = pressure,
                stabilize = random.nextInt(3), nib = random.nextInt(180),
            )
            val nib = tool.liveNib(null)
            for (form in 0..2) for ((light, heavy) in bands) {
                val samples = raw(random, 1 + random.nextInt(150), form, light, heavy)
                val drawn = LazyBrush.smooth(samples, tool.stabilize)
                if (InkWidth.widths(drawn, InkKind.PEN, brush, pressure, size, live = true, nib = nib) == null) {
                    // No widths: the plain polyline, which reads none.
                    assertTrue("$tool", LiveLine(tool).frame(samples, emptyList(), null) is LiveLine.Stroke)
                    continue
                }
                ribbons++
                val live = LiveWidths(tool)
                var shown = 0
                while (shown < drawn.size) {
                    shown = min(drawn.size, shown + 1 + random.nextInt(6))
                    val so = drawn.subList(0, shown)
                    live.update(so)
                    val widths = InkWidth.widths(so, InkKind.PEN, brush, pressure, size, live = true, nib = nib)!!
                    for (i in widths.indices) assertEquals("$tool, form $form, point $i of $shown", widths[i], live.width(i), 0.0)
                    val mean = InkWidth.opacities(so, InkKind.PEN, brush, pressure)?.average()
                    assertEquals("$tool, form $form, at $shown", mean, live.opacity)
                    assertEquals("$tool, form $form, at $shown", mean?.let(InkWidth::opacityBand) ?: 1.0, live.shade, 0.0)
                }
                if (brush == Brush.BRUSH) {
                    // The points within the start taper, where the width eases in along the stroke.
                    var arc = 0.0
                    for (i in 1 until drawn.size) {
                        arc += hypot(drawn[i][0] - drawn[i - 1][0], drawn[i][1] - drawn[i - 1][1])
                        if (arc > 0 && arc < InkWidth.TAPER_START * max(size, 0.01)) tapered++
                    }
                }
            }
        }
        // Pen, Pencil and Calligraphy with pressure, Calligraphy and Brush without, the Brush with, at every size, form and band.
        assertEquals(6 * 4 * 3 * 3, ribbons)
        assertTrue(tapered > 100)
    }

    @Test
    fun `the lazy brush frame by frame is the lazy brush over the whole stroke`() {
        val random = Random(11)
        for (level in 0..4) {
            val tool = InkTool(InkKind.PEN, "#000000", 2.0, brush = Brush.MONOLINE, stabilize = level)
            repeat(5) {
                val samples = stroke(random, 1 + random.nextInt(150))
                val line = LiveLine(tool)
                var shown = 0
                while (shown < samples.size) {
                    shown = min(samples.size, shown + 1 + random.nextInt(8))
                    val so = samples.subList(0, shown)
                    val look = line.frame(so, emptyList(), null) as LiveLine.Stroke
                    val expected = LazyBrush.smooth(so, level)
                    val drawn = look.settled + look.tail
                    assertEquals(expected.size, drawn.size)
                    for (i in expected.indices) assertArrayEquals(expected[i], drawn[i], 0.0)
                }
            }
        }
    }

    @Test
    fun `the colour is the tool's at its opacity, a Pencil's shaded by its band`() {
        val red = InkTool(InkKind.PEN, "#ff0000", 2.0, opacity = 0.5, brush = Brush.MONOLINE)
        val look = LiveLine(red).frame(listOf(doubleArrayOf(1.0, 2.0, 0.5, 0.0)), emptyList(), null)
        assertEquals(CssColor.withAlpha(0xffff0000.toInt(), 0.5), look.argb)
        // An eight-digit colour's alpha is replaced, as UIColor.withAlphaComponent does.
        val faded = InkTool(InkKind.PEN, "#ff000080", 2.0, brush = Brush.MONOLINE)
        assertEquals(0xffff0000.toInt(), LiveLine(faded).frame(listOf(doubleArrayOf(1.0, 2.0, 0.5, 0.0)), emptyList(), null).argb)
        val pencil = InkTool(InkKind.PEN, "#000000", 1.6, opacity = 0.8, brush = Brush.PENCIL)
        val light = LiveLine(pencil).frame(listOf(doubleArrayOf(0.0, 0.0, 0.2, 0.0), doubleArrayOf(3.0, 0.0, 0.2, 4.0)), emptyList(), null)
        // opacity(0.2) = 0.32, whose band is 0.33
        assertEquals(CssColor.withAlpha(0xff000000.toInt(), 0.8 * 0.33), light.argb)
        val unknown = InkTool(InkKind.PEN, "red", 2.0, brush = Brush.MONOLINE)
        assertEquals(LiveLine.INK, LiveLine(unknown).frame(listOf(doubleArrayOf(1.0, 2.0, 0.5, 0.0)), emptyList(), null).argb)
    }

    @Test
    fun `a dashed line, a highlighter and a plain line are polylines at the tool's width`() {
        val samples = listOf(doubleArrayOf(0.0, 0.0, 0.9, 0.0), doubleArrayOf(5.0, 0.0, 0.2, 4.0), doubleArrayOf(9.0, 1.0, 0.7, 8.0))
        val dashed = LiveLine(InkTool(InkKind.PEN, "#000000", 3.0, dash = listOf(6.0, 6.0))).frame(samples, emptyList(), null)
        dashed as LiveLine.Stroke
        assertEquals(listOf(6.0, 6.0), dashed.dash)
        assertEquals(3.0, dashed.width, 0.0)
        assertEquals(2, dashed.settled.size)
        assertEquals(1, dashed.tail.size)
        assertFalse(dashed.outline)
        val highlighter = LiveLine(InkTool(InkKind.HIGHLIGHTER, "#fde047", 14.0, opacity = 0.4, multiply = true)).frame(samples, emptyList(), null)
        assertTrue(highlighter is LiveLine.Stroke)
        assertNull((highlighter as LiveLine.Stroke).dash)
        val thin = LiveLine(InkTool(InkKind.PEN, "#000000", 0.1, pressure = false)).frame(samples, emptyList(), null) as LiveLine.Stroke
        assertEquals(0.5, thin.width, 0.0)
    }

    @Test
    fun `a single sample is a dot, with the prediction after it`() {
        val tool = InkTool(InkKind.PEN, "#000000", 2.0, brush = Brush.MONOLINE)
        val dot = LiveLine(tool).frame(listOf(doubleArrayOf(4.0, 5.0, 0.5, 0.0)), emptyList(), null) as LiveLine.Stroke
        assertTrue(dot.settled.isEmpty())
        assertEquals(1, dot.tail.size)
        val ahead = LiveLine(tool).frame(listOf(doubleArrayOf(4.0, 5.0, 0.5, 0.0)), listOf(doubleArrayOf(6.0, 5.0)), null) as LiveLine.Stroke
        assertEquals(2, ahead.tail.size)
        assertTrue(LiveLine(tool).frame(emptyList(), emptyList(), null).isEmpty)
    }

    @Test
    fun `smoothing leaves the prediction out`() {
        val samples = listOf(doubleArrayOf(0.0, 0.0, 0.5, 0.0), doubleArrayOf(20.0, 0.0, 0.5, 4.0))
        val ahead = listOf(doubleArrayOf(25.0, 0.0))
        val smooth = LiveLine(InkTool(InkKind.PEN, "#000000", 2.0, brush = Brush.MONOLINE, stabilize = 1)).frame(samples, ahead, null)
        assertEquals(1, (smooth as LiveLine.Stroke).tail.size)
        val plain = LiveLine(InkTool(InkKind.PEN, "#000000", 2.0, brush = Brush.MONOLINE)).frame(samples, ahead, null)
        assertEquals(2, (plain as LiveLine.Stroke).tail.size)
    }

    @Test
    fun `along the ruler the line runs between the outermost projections, without prediction`() {
        val tool = InkTool(InkKind.PEN, "#000000", 2.0, snap = Snap(100.0, 200.0, 0.0), dash = listOf(2.0, 2.0))
        val samples = listOf(doubleArrayOf(150.0, 195.0, 0.5, 0.0), doubleArrayOf(260.0, 205.0, 0.5, 4.0), doubleArrayOf(330.0, 190.0, 0.5, 8.0))
        val look = LiveLine(tool).frame(samples, listOf(doubleArrayOf(400.0, 200.0)), null) as LiveLine.Stroke
        assertTrue(look.outline)
        assertFalse(look.shape)
        assertEquals(listOf(2.0, 2.0), look.dash)
        assertTrue(look.settled.isEmpty())
        assertEquals(2, look.tail.size)
        assertArrayEquals(doubleArrayOf(150.0, 200.0), look.tail[0], 1e-9)
        assertArrayEquals(doubleArrayOf(330.0, 200.0), look.tail[1], 1e-9)
    }

    @Test
    fun `a held shape is its outline, even end to end, whatever the brush`() {
        val tool = InkTool(InkKind.PEN, "#000000", 3.0, brush = Brush.BRUSH, snap = Snap(0.0, 0.0, 0.0))
        val rect = HeldShape("rect", mapOf("cx" to 0.0, "cy" to 0.0, "w" to 40.0, "h" to 20.0))
        val line = LiveLine(tool)
        val look = line.frame(listOf(doubleArrayOf(0.0, 0.0, 0.5, 0.0)), emptyList(), rect) as LiveLine.Stroke
        assertTrue(look.shape)
        assertTrue(look.outline)
        assertTrue(look.closed)
        assertEquals(4, look.tail.size)
        assertEquals(3.0, look.width, 0.0)
        // While the shape stands its outline is the one made before; a refit makes it again.
        val again = line.frame(listOf(doubleArrayOf(0.0, 0.0, 0.5, 0.0)), emptyList(), rect.copy()) as LiveLine.Stroke
        assertSame(look.tail, again.tail)
        val wider = rect.refit(30.0, 0.0, 20.0, 0.0)
        val refit = line.frame(listOf(doubleArrayOf(0.0, 0.0, 0.5, 0.0)), emptyList(), wider) as LiveLine.Stroke
        assertEquals(wider.outline().map { it.toList() }, refit.tail.map { it.toList() })
        // The shape dropped: freehand again (here along the ruler).
        assertFalse((line.frame(listOf(doubleArrayOf(0.0, 0.0, 0.5, 0.0)), emptyList(), null) as LiveLine.Stroke).shape)
    }

    @Test
    fun `the prediction runs at most 16 ms and 12 CSS px ahead`() {
        val last = doubleArrayOf(10.0, 10.0, 0.5, 40.0)
        val predicted = listOf(
            doubleArrayOf(10.5, 10.0, 0.0),
            doubleArrayOf(11.0, 10.0, 4.0),
            doubleArrayOf(14.0, 10.0, 8.0),
            doubleArrayOf(30.0, 10.0, 12.0),
            doubleArrayOf(15.0, 10.0, 14.0),
        )
        val ahead = LiveLine.ahead(last, predicted)
        assertEquals(2, ahead.size)
        assertArrayEquals(doubleArrayOf(11.0, 10.0), ahead[0], 0.0)
        assertArrayEquals(doubleArrayOf(14.0, 10.0), ahead[1], 0.0)
        val late = LiveLine.ahead(last, listOf(doubleArrayOf(11.0, 10.0, 8.0), doubleArrayOf(12.0, 10.0, 17.0), doubleArrayOf(12.5, 10.0, 18.0)))
        assertEquals(1, late.size)
    }
}
