package com.gammapdf.gamma.pen

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.core.graphics.withSave
import androidx.core.graphics.withScale
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The captured stroke's line, drawn natively over the page a frame or more
 * ahead of what the page could paint (docs/dev/android.md "The pen"), the
 * counterpart of ipad/GammaIPad/Pencil/LiveStrokeView.swift: LiveLine says
 * what the line is, in CSS px, and this draws it at the display's density.
 * The saved stroke is the page's; each line stays until the page says it
 * painted that stroke (`release`), so the swap never blinks, and strokes
 * written in quick succession each keep theirs. It takes no touches.
 *
 * The line being written goes to the front buffer of a transparent SurfaceView
 * above the page (graphics-core's CanvasFrontBufferedRenderer), which the
 * display shows while it is drawn into, so the line keeps up with the pen.
 * Each frame clears and draws again only the part of the buffer the line
 * changed in (LiveLine's tail and what settled since), the rest holding the
 * same pixels; the whole buffer at the stroke's first frame, when its colour
 * changes and when the line turns into an outline or back. At the lift the
 * line goes to the renderer's multi-buffered layer by a commit, which hides
 * the front buffer in the same transaction, and stays there until it is
 * released. That layer is drawn whole at every commit, from the lines kept,
 * and a commit hides the front buffer, so a line released while another is
 * being written goes at that one's lift (or cancel), not mid-stroke.
 *
 * A highlighter multiplied onto the page, as the page paints it, is drawn by
 * a plain View under the SurfaceView instead: the SurfaceView's layers are
 * composited over the window with ordinary alpha and never read the page's
 * pixels, while a View's draw with BlendMode.MULTIPLY blends with what the
 * WebView drew beneath it in the same frame (where it does not, the
 * highlighter shows at its opacity, as on the iPad). That View draws every
 * line when front buffering is out: the renderer could not be made, or the
 * surface is not there yet when the stroke starts.
 */
class LiveInk(context: Context) {
    /** A finished stroke's line, waiting for the page's paint. */
    class Handle internal constructor(internal val line: Line)

    /** A line as drawn. Never changed once made: the render thread may be drawing it. */
    internal class Line(val path: Path, val paint: Paint, val front: Boolean)

    /**
     * One frame of the front buffer: `dirty` (px; the whole buffer when null)
     * cleared, and `path` drawn within it. The renderer keeps every frame it
     * is given until the stroke ends (the commit at its lift, or its cancel),
     * and a polyline's path is the whole line so far, so a long stroke's
     * frames would hold its line as many times as it has frames: a frame lets
     * its path go once it is drawn.
     */
    private class Frame(path: Path, val paint: Paint, val dirty: Rect?, val scale: Float) {
        @Volatile var path: Path? = path
    }

    private class Active(tool: InkTool, val front: Boolean) {
        val geometry = LiveLine(tool)
        val multiply = tool.multiply
        /** The line's settled part (LiveLine), kept from frame to frame, and how much of it is in. */
        val settled = Path()
        var settledCount = 0
        var look: LiveLine.Look? = null
        /** The line's own paint, the flash's, and the one the last frame was drawn with. */
        var paint: Paint? = null
        var flashPaint: Paint? = null
        var shownPaint: Paint? = null
        /** The last frame's line, when the View draws it. */
        var viewLine: Line? = null
        var flashed = false
        var flashUntil = 0L
        /** The last outline drawn (LiveLine reuses it while the shape stands) and its path. */
        var outlineOf: List<DoubleArray>? = null
        var outlinePath: Path? = null
        /** What the last frame changed (CSS px), and whether it was an outline: the next one clears that too. */
        var changed = RectF()
        var outline = false
    }

    /** The View lines are drawn by: multiplied highlighters, and every line without front buffering. */
    val overlay: View = Overlay(context)

    /** The front-buffered surface, above the page and the overlay. */
    val surface: SurfaceView = SurfaceView(context).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var renderer: CanvasFrontBufferedRenderer<Frame>? = null
    /** The surface has its size, so the renderer can draw (surfaceChanged comes after the renderer's own). */
    private var surfaceReady = false
    /** The lines kept in the multi-buffered layer, which the render thread draws at each commit. */
    @Volatile private var frontKept: List<Line> = emptyList()
    private val viewKept = ArrayList<Line>()
    private var active: Active? = null
    /** A front line was released while another was being written: the layer is drawn again at that one's end. */
    private var pendingCommit = false
    /** CSS px to the buffers' px. */
    @Volatile private var scale = context.resources.displayMetrics.density
    /** The multi-buffered layer was drawn, which hides and clears the front buffer: the next frame is drawn whole. */
    private val frontLost = AtomicBoolean(false)
    private val flashEnd = Runnable { endFlash() }

    init {
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceReady = true
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })
    }

    /** Makes the front-buffer renderer (once the views are in a window); without one, every line is the View's. */
    fun attach() {
        if (renderer != null) return
        renderer = try {
            CanvasFrontBufferedRenderer(surface, Draw())
        } catch (e: Exception) {
            Log.w(TAG, "no front-buffered rendering; the live line is drawn by a view", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "no front-buffered rendering; the live line is drawn by a view", e)
            null
        }
        // A surface nobody draws into stays out of the way.
        surface.visibility = if (renderer != null) View.VISIBLE else View.GONE
    }

    /** Lets every line go and the renderer with them, as the views leave the window. */
    fun detach() {
        cancel()
        frontKept = emptyList()
        viewKept.clear()
        pendingCommit = false
        renderer?.release(true)
        renderer = null
        overlay.invalidate()
    }

    /**
     * The stroke in progress: its samples (`[x, y, p, t, …]`, StrokeRecorder),
     * the predicted points after them and the shape the page snapped it to, if
     * any, which is drawn instead, in the selection blue for its first frames
     * (the page's SNAP_BLUE, once per stroke).
     */
    fun show(samples: List<DoubleArray>, predicted: List<DoubleArray>, shape: HeldShape?, tool: InkTool) {
        val a = active ?: Active(tool, front = !tool.multiply && renderer?.isValid() == true && surfaceReady)
            .also { active = it }
        scale = overlay.resources.displayMetrics.density
        val look = a.geometry.frame(samples, predicted, shape)
        if (look.isEmpty) return
        a.look = look
        val changed = settle(a, look)
        val now = SystemClock.uptimeMillis()
        val isShape = look is LiveLine.Stroke && look.shape
        if (isShape && !a.flashed) {
            a.flashed = true
            a.flashUntil = now + FLASH_MS
            overlay.postDelayed(flashEnd, FLASH_MS)
        } else if (!isShape) {
            // a shape dropped while it flashed: no blue on the freehand line
            a.flashUntil = 0
        }
        val paint = plainPaint(a, look)
        val shown = if (isShape && now < a.flashUntil) flashPaint(a, look as LiveLine.Stroke) else paint
        present(a, look, shown, changed)
    }

    /** The stroke ended: its line, as last shown in its own colour, stays until `release`. */
    fun finish(): Handle? {
        val a = active ?: return null
        active = null
        overlay.removeCallbacks(flashEnd)
        val look = a.look ?: return null
        val line = Line(whole(a, look), a.paint ?: return null, a.front)
        if (a.front) {
            frontKept = frontKept + line
            pendingCommit = false
            renderer?.commit()
        } else {
            viewKept.add(line)
            overlay.invalidate()
        }
        return Handle(line)
    }

    /** The stroke was lost (a cancelled touch, or the page took the hold): its line goes now. */
    fun cancel() {
        val a = active ?: return
        active = null
        overlay.removeCallbacks(flashEnd)
        if (a.front) {
            renderer?.cancel()
            if (pendingCommit) {
                pendingCommit = false
                renderer?.commit()
            }
        } else {
            overlay.invalidate()
        }
    }

    /** The page painted the stroke (or gave up): its line goes. Releasing twice is releasing once. */
    fun release(handle: Handle) {
        val line = handle.line
        if (line.front) {
            if (line !in frontKept) return
            frontKept = frontKept - line
            if (active?.front == true) pendingCommit = true else renderer?.commit()
        } else if (viewKept.remove(line)) {
            overlay.invalidate()
        }
    }

    /**
     * Adds what settled since the last frame to the stroke's kept path, and
     * gives what this frame changes against the last, in CSS px: what settled,
     * and the tail, with the last settled point a polyline's tail joins on.
     */
    private fun settle(a: Active, look: LiveLine.Look): RectF {
        val changed = RectF()
        when (look) {
            is LiveLine.Fill -> {
                for (i in a.settledCount until look.settled.size) add(a.settled, look.settled[i], changed)
                a.settledCount = look.settled.size
                for (piece in look.tail) grow(changed, piece)
            }
            is LiveLine.Stroke -> {
                val half = (look.width / 2).toFloat()
                for (i in a.settledCount until look.settled.size) point(a.settled, look.settled[i], i == 0, half, changed)
                a.settledCount = look.settled.size
                look.settled.lastOrNull()?.let { grow(changed, it, half) }
                for (p in look.tail) grow(changed, p, half)
            }
        }
        return changed
    }

    /** The frame's whole line: the kept settled part and the tail. */
    private fun whole(a: Active, look: LiveLine.Look): Path = when (look) {
        is LiveLine.Fill -> Path(a.settled).also { path -> for (piece in look.tail) add(path, piece, null) }
        is LiveLine.Stroke -> a.outlinePath?.takeIf { look.outline && look.tail === a.outlineOf } ?: polyline(a, look)
    }

    /** A polyline's path; a held shape's outline is kept while the shape stands, as LiveLine keeps its points. */
    private fun polyline(a: Active, look: LiveLine.Stroke): Path {
        val path = if (look.settled.isEmpty()) Path() else Path(a.settled)
        for ((j, p) in look.tail.withIndex()) point(path, p, look.settled.isEmpty() && j == 0, 0f, null)
        // a dot: a zero-length segment, which the round cap draws as a disc
        if (look.settled.isEmpty() && look.tail.size == 1) path.lineTo(look.tail[0][0].toFloat(), look.tail[0][1].toFloat())
        if (look.closed) path.close()
        if (look.outline) {
            a.outlineOf = look.tail
            a.outlinePath = path
        }
        return path
    }

    /**
     * What of a ribbon is drawn within `dirty` (CSS px): the settled pieces
     * that reach into it, and the tail. A long stroke is thousands of
     * pieces, and those away from the pen add nothing inside the clip.
     */
    private fun near(look: LiveLine.Fill, dirty: RectF): Path {
        val path = Path()
        val box = RectF()
        for (piece in look.settled) {
            box.setEmpty()
            grow(box, piece)
            if (RectF.intersects(box, dirty)) add(path, piece, null)
        }
        for (piece in look.tail) add(path, piece, null)
        return path
    }

    private fun present(a: Active, look: LiveLine.Look, paint: Paint, changed: RectF) {
        val outline = look is LiveLine.Stroke && look.outline
        val lost = frontLost.getAndSet(false)
        val all = lost || a.shownPaint !== paint || outline != a.outline
        val dirty = RectF(changed).apply { union(a.changed) }
        a.shownPaint = paint
        a.changed = changed
        a.outline = outline
        if (!a.front) {
            a.viewLine = Line(whole(a, look), paint, front = false)
            overlay.invalidate()
            return
        }
        val renderer = renderer ?: return
        val clip = if (all) null else pixels(dirty)
        val path = if (clip == null || look !is LiveLine.Fill) whole(a, look) else near(look, css(clip))
        renderer.renderFrontBufferedLayer(Frame(path, paint, clip, scale))
    }

    /** The shape's flash is over: the line again in its own colour. */
    private fun endFlash() {
        val a = active ?: return
        val look = a.look ?: return
        val paint = a.paint ?: return
        if (a.shownPaint === paint) return
        present(a, look, paint, RectF(a.changed))
    }

    private fun plainPaint(a: Active, look: LiveLine.Look): Paint {
        val fill = look is LiveLine.Fill
        a.paint?.let { if (it.color == look.argb && (it.style == Paint.Style.FILL) == fill) return it }
        return paint(look, look.argb, a.multiply).also { a.paint = it }
    }

    private fun flashPaint(a: Active, look: LiveLine.Stroke): Paint =
        a.flashPaint ?: paint(look, SNAP_BLUE, a.multiply).also { a.flashPaint = it }

    private fun paint(look: LiveLine.Look, argb: Int, multiply: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        if (look is LiveLine.Stroke) {
            style = Paint.Style.STROKE
            strokeWidth = look.width.toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            look.dash?.let { pathEffect = DashPathEffect(floatArrayOf(it[0].toFloat(), it[1].toFloat()), 0f) }
        }
        if (multiply) blendMode = BlendMode.MULTIPLY
    }

    /** A clip in px as CSS px, with the antialiasing's pixel around it: what a piece must reach into to show in it. */
    private fun css(r: Rect): RectF =
        RectF(r.left / scale - 1, r.top / scale - 1, r.right / scale + 1, r.bottom / scale + 1)

    /** CSS px to the buffer's px, rounded out, with room for the antialiasing. */
    private fun pixels(r: RectF): Rect = Rect(
        floor(r.left * scale).toInt() - MARGIN, floor(r.top * scale).toInt() - MARGIN,
        ceil(r.right * scale).toInt() + MARGIN, ceil(r.bottom * scale).toInt() + MARGIN,
    )

    /** The surface's two layers, drawn on the renderer's thread. */
    private inner class Draw : CanvasFrontBufferedRenderer.Callback<Frame> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Frame) {
            // A frame comes here once (graphics-core 1.0.4), to be recorded into a display list,
            // which keeps its own copy of the path; one drawn already is in the buffer.
            val path = param.path ?: return
            param.path = null
            canvas.withSave {
                param.dirty?.let { clipRect(it) }
                drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
                scale(param.scale, param.scale)
                drawPath(path, param.paint)
            }
        }

        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Frame>) {
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
            val s = scale
            canvas.scale(s, s)
            for (line in frontKept) canvas.drawPath(line.path, line.paint)
        }

        override fun onMultiBufferedLayerRenderComplete(
            frontBufferedLayerSurfaceControl: SurfaceControlCompat,
            multiBufferedLayerSurfaceControl: SurfaceControlCompat,
            transaction: SurfaceControlCompat.Transaction,
        ) {
            frontLost.set(true)
        }
    }

    /** The lines the View draws: the kept ones, then the one being written. */
    private inner class Overlay(context: Context) : View(context) {
        init {
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val current = active?.takeIf { !it.front }?.viewLine
            if (viewKept.isEmpty() && current == null) return
            canvas.withScale(scale, scale) {
                for (line in viewKept) drawPath(line.path, line.paint)
                current?.let { drawPath(it.path, it.paint) }
            }
        }
    }

    companion object {
        private const val TAG = "GammaPen"

        /** A held shape's colour while it flashes: the page's SNAP_BLUE (InkLayer.jsx), the selection's. */
        const val SNAP_BLUE = 0xff508cff.toInt()

        /** How long the flash lasts: the page's two frames at 60 Hz. */
        const val FLASH_MS = 34L

        /** Pixels around a frame's changes cleared and drawn with them: the antialiasing's reach. */
        private const val MARGIN = 2

        private fun add(path: Path, piece: Ribbon.Piece, changed: RectF?) {
            when (piece) {
                is Ribbon.Piece.Disc -> path.addCircle(piece.x.toFloat(), piece.y.toFloat(), piece.r.toFloat(), Path.Direction.CW)
                is Ribbon.Piece.Quad -> {
                    val p = piece.pts
                    path.moveTo(p[0].toFloat(), p[1].toFloat())
                    for (k in 1 until 4) path.lineTo(p[2 * k].toFloat(), p[2 * k + 1].toFloat())
                    path.close()
                }
            }
            changed?.let { grow(it, piece) }
        }

        private fun point(path: Path, p: DoubleArray, first: Boolean, half: Float, changed: RectF?) {
            if (first) path.moveTo(p[0].toFloat(), p[1].toFloat()) else path.lineTo(p[0].toFloat(), p[1].toFloat())
            changed?.let { grow(it, p, half) }
        }

        private fun grow(box: RectF, p: DoubleArray, half: Float) {
            val x = p[0].toFloat()
            val y = p[1].toFloat()
            box.union(x - half, y - half, x + half, y + half)
        }

        private fun grow(box: RectF, piece: Ribbon.Piece) {
            when (piece) {
                is Ribbon.Piece.Disc -> {
                    val x = piece.x.toFloat()
                    val y = piece.y.toFloat()
                    val r = piece.r.toFloat()
                    box.union(x - r, y - r, x + r, y + r)
                }
                is Ribbon.Piece.Quad -> {
                    val p = piece.pts
                    var left = p[0]
                    var top = p[1]
                    var right = p[0]
                    var bottom = p[1]
                    for (k in 1 until 4) {
                        left = minOf(left, p[2 * k])
                        right = maxOf(right, p[2 * k])
                        top = minOf(top, p[2 * k + 1])
                        bottom = maxOf(bottom, p[2 * k + 1])
                    }
                    box.union(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                }
            }
        }
    }
}
