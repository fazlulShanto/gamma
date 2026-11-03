package com.gammapdf.gamma.pen

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.ceil

/**
 * The page with the pen over it (docs/dev/android.md "The pen"): a FrameLayout
 * holding only the page's WebView and the live line (LiveInk), whose
 * dispatchTouchEvent sees every touch the page gets, so the bar and the
 * launcher, outside it, never reach the pen. The Android counterpart of the
 * iPad's PencilCapture on the web view's container
 * (ipad/GammaIPad/Pencil/PencilCapture.swift, Shell/ShellView.swift): PenRouter
 * says what the page gets of each event and which stylus pointers are
 * recorded, PenCapture records them and takes the one the page claims, and
 * this turns MotionEvents into both and the page's events back out. It never
 * gets in the page's way until a claim: the page gets every event as it came,
 * the WebView deciding as in Chrome.
 *
 * Samples are in the page's CSS px: the page is laid out at 1 CSS px per dp,
 * so a MotionEvent's px over the density. A stylus's pressure is read where
 * its device has a pressure axis (else the samples say 0.5) and its pose
 * where it has a tilt axis (Tilt). The touch stream is unbuffered from a
 * stylus's down, so every sample its digitizer measures comes as it is
 * measured, and the captured stroke's line runs ahead of the pen by the
 * motion predictor's points. Hover and button events go to the page
 * untouched, after the pen's buttons (PenGestures) see them.
 */
@SuppressLint("ViewConstructor")
class PenLayer(context: Context) : FrameLayout(context) {
    /** What the shell does for the pen, all on the main thread. */
    interface Listener {
        /** A claimed stroke ended: send `stroke`, then `release(line)` once answered, or after a while. */
        fun onStroke(pointerId: Int, samples: List<DoubleArray>, held: Int?, line: LiveInk.Handle?)

        /** A claimed stroke was cancelled: send `stroke` with no samples. */
        fun onAbandon(pointerId: Int)

        /** The captured stylus held still: ask `hold`, then hand the answer to `held(pointerId, reply)`. */
        fun onHold(pointerId: Int, samples: List<DoubleArray>)

        /** A pen button clicked out of contact: send `pencil`. */
        fun onPenButton(gesture: String, action: String)
    }

    var listener: Listener? = null

    private val live = LiveInk(context)
    private val router = PenRouter()
    private val capture = PenCapture(Output())
    private val gestures = PenGestures { gesture, action -> listener?.onPenButton(gesture, action) }
    private val timer = Handler(Looper.getMainLooper())
    private val fire = Runnable { capture.fire(now()) }
    private var page: View? = null
    private var predictor: MotionEventPredictor? = null
    /** The page's stream as it last went, for the ACTION_CANCEL that ends it. */
    private var lastGiven: MotionEvent? = null
    /** The down time of the page's stream, which a stream of its own starts later than the gesture. */
    private var streamDown = 0L
    /** A stylus went down as a later pointer: the stream is made unbuffered at its next move. */
    private var unbufferLater = false
    /** Which axes each input device reports, by device id. */
    private val axes = HashMap<Int, Axes>()

    private class Axes(val pressure: Boolean, val tilt: Boolean)

    init {
        addView(live.overlay, LayoutParams(MATCH, MATCH))
        addView(live.surface, LayoutParams(MATCH, MATCH))
    }

    /**
     * Puts `view` (the page's WebView) under the live line, in place of the
     * page before (a new one after the renderer's death, say). A stroke in
     * progress is the old page's: it is abandoned, and the rest of its
     * gesture is swallowed.
     */
    fun setPage(view: View) {
        if (view === page) return
        endGesture(tellPage = false)
        page?.let { removeView(it) }
        (view.parent as? ViewGroup)?.removeView(view)
        addView(view, 0, LayoutParams(MATCH, MATCH))
        page = view
    }

    /**
     * The page's claim for its `pointerId`, which went down at (x, y), its
     * viewport CSS px, to be drawn with `tool`: true when that stylus stroke
     * was still down to take. The page's stream then ends, and the stroke is
     * drawn here and handed back at its lift (Listener.onStroke).
     */
    fun claim(pointerId: Int, x: Double, y: Double, tool: InkTool): Boolean {
        val token = capture.claim(pointerId, x, y, tool, now()) ?: return false
        router.take(token)?.let(::cancelPage)
        return true
    }

    /** The page's answer to the `hold` asked for `pointerId` (PenCapture.held). */
    fun held(pointerId: Int, reply: HoldReply) = capture.held(pointerId, reply)

    /** The page painted the stroke `line` was drawn for (or gave up): the line goes. */
    fun release(line: LiveInk.Handle?) {
        line?.let(live::release)
    }

    /**
     * Ends whatever the pen was doing, as when the page goes: a captured stroke
     * is abandoned (Listener.onAbandon), nothing recorded can be claimed, and
     * the rest of the gesture under way is not the page's.
     */
    fun reset() = endGesture(tellPage = true)

    /** MainActivity.dispatchKeyEvent asks first: true when the key was a stylus button's, which the pen takes. */
    fun dispatchStylusKey(event: KeyEvent): Boolean = gestures.key(event)

    /** The window lost focus: a button press is forgotten, so its release, wherever it lands, clicks nothing. */
    fun onWindowFocusLost() = gestures.reset()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val page = page ?: return false
        val action = event.actionMasked
        // A gesture left unfinished (another device's) ends here, as Android ends one device's for the next's.
        if (action == MotionEvent.ACTION_DOWN && router.active) endGesture(tellPage = true)
        if (PenGestures.isStylus(event)) {
            gestures.motion(event, inContact = true)
            unbuffer(event)
        }
        val n = event.pointerCount
        val ids = IntArray(n) { event.getPointerId(it) }
        val tools = IntArray(n) { event.getToolType(it) }
        val canceled = canceled(event)
        val route = router.route(action, ids[event.actionIndex], ids, tools, event.buttonState, canceled, event.deviceId)
        if (route.accepted) predict(event)
        record(event, route.tokens, canceled)
        route.forward?.let { give(page, event, it) }
        return true
    }

    // The page's requests go on up: this layer intercepts nothing, and its own flag, which a
    // ViewGroup clears at each down in the dispatch it does not run, would stop the next one.
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        gestures.motion(event, inContact = false)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        live.attach()
    }

    override fun onDetachedFromWindow() {
        reset()
        live.detach()
        predictor = null
        super.onDetachedFromWindow()
    }

    /** The page's stream ends here (sent to the page unless it is going), and the capture's strokes with it. */
    private fun endGesture(tellPage: Boolean) {
        val cancel = router.abort()
        if (cancel != null && tellPage) cancelPage(cancel) else forgetGiven()
        capture.reset(now())
    }

    /**
     * Unbuffered dispatch from a stylus's down to the gesture's end: every
     * sample as the digitizer measures it. The request is taken at an
     * ACTION_DOWN or an ACTION_MOVE, so a stylus that joins a gesture asks at
     * its next move.
     */
    private fun unbuffer(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                unbufferLater = false
                requestUnbufferedDispatch(event)
            }
            MotionEvent.ACTION_POINTER_DOWN ->
                if (PenRouter.isStylus(event.getToolType(event.actionIndex))) unbufferLater = true
            MotionEvent.ACTION_MOVE -> if (unbufferLater) {
                unbufferLater = false
                requestUnbufferedDispatch(event)
            }
        }
    }

    /** The motion predictor sees every event of the gesture (it keeps its own state for each pointer). */
    private fun predict(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && predictor == null) {
            predictor = MotionEventPredictor.newInstance(this)
        }
        try {
            predictor?.record(event)
        } catch (e: IllegalArgumentException) {
            // A stream the system's predictor will not take: no prediction until the next gesture.
            predictor = null
        }
    }

    /** The stylus samples an event carries for the strokes recorded (`tokens`, by pointer index; 0: not recorded). */
    private fun record(event: MotionEvent, tokens: LongArray, canceled: Boolean) {
        val i = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (tokens[i] != 0L) add(tokens[i], event, i, CURRENT)
            MotionEvent.ACTION_MOVE -> {
                for (k in tokens.indices) {
                    val token = tokens[k]
                    if (token == 0L) continue
                    for (h in 0 until event.historySize) add(token, event, k, h)
                    add(token, event, k, CURRENT)
                }
                for (k in tokens.indices) {
                    val token = tokens[k]
                    if (token == 0L) continue
                    capture.moved(token, if (token == capture.captured && capture.predicts) predicted(event, k) else emptyList())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val token = tokens[i]
                if (token == 0L) return
                if (canceled) {
                    capture.cancelled(token, time(event, CURRENT))
                } else {
                    add(token, event, i, CURRENT)
                    capture.lifted(token, time(event, CURRENT))
                }
            }
            MotionEvent.ACTION_CANCEL -> for (token in tokens) if (token != 0L) capture.cancelled(token, time(event, CURRENT))
        }
    }

    /** Pointer `i`'s sample at history position `h` (CURRENT: the event's own). */
    private fun add(token: Long, event: MotionEvent, i: Int, h: Int) {
        val page = page ?: return
        val axes = axes(event)
        val density = resources.displayMetrics.density
        val x = ((if (h == CURRENT) event.getX(i) else event.getHistoricalX(i, h)) - page.left) / density.toDouble()
        val y = ((if (h == CURRENT) event.getY(i) else event.getHistoricalY(i, h)) - page.top) / density.toDouble()
        val pressure = (if (h == CURRENT) event.getPressure(i) else event.getHistoricalPressure(i, h)).toDouble()
        val maxForce = if (axes.pressure) 1.0 else 0.0
        val time = time(event, h)
        if (axes.tilt) {
            val tilt = axis(event, MotionEvent.AXIS_TILT, i, h)
            val orientation = axis(event, MotionEvent.AXIS_ORIENTATION, i, h)
            capture.record(token, x, y, pressure, maxForce, time, Tilt.altitude(tilt), Tilt.azimuth(orientation))
        } else {
            capture.record(token, x, y, pressure, maxForce, time)
        }
    }

    /** The predicted points after pointer `i`'s sample (LiveLine.ahead), in CSS px. */
    private fun predicted(event: MotionEvent, i: Int): List<DoubleArray> {
        val page = page ?: return emptyList()
        val ahead = predictor?.predict() ?: return emptyList()
        try {
            val k = ahead.findPointerIndex(event.getPointerId(i))
            if (k < 0) return emptyList()
            val density = resources.displayMetrics.density.toDouble()
            val left = page.left.toDouble()
            val top = page.top.toDouble()
            val last = doubleArrayOf((event.getX(i) - left) / density, (event.getY(i) - top) / density)
            val points = ArrayList<DoubleArray>(ahead.historySize + 1)
            for (h in 0 until ahead.historySize) {
                points.add(doubleArrayOf(
                    (ahead.getHistoricalX(k, h) - left) / density, (ahead.getHistoricalY(k, h) - top) / density,
                    (ahead.getHistoricalEventTime(h) - event.eventTime).toDouble(),
                ))
            }
            points.add(doubleArrayOf((ahead.getX(k) - left) / density, (ahead.getY(k) - top) / density, (ahead.eventTime - event.eventTime).toDouble()))
            return LiveLine.ahead(last, points)
        } finally {
            ahead.recycle()
        }
    }

    /** What the page gets of the event: the event as it came, or one made of the pointers its stream has. */
    private fun give(page: View, event: MotionEvent, forward: PenRouter.Forward) {
        if (forward.action == MotionEvent.ACTION_DOWN) streamDown = if (forward.fresh) event.eventTime else event.downTime
        val out = if (forward.whole && forward.erasers.none { it }) event else rebuild(event, forward, event.eventTime) ?: return
        forgetGiven()
        if (forward.action != MotionEvent.ACTION_UP && forward.action != MotionEvent.ACTION_CANCEL) {
            lastGiven = MotionEvent.obtainNoHistory(out)
        }
        deliver(page, out)
        if (out !== event) out.recycle()
    }

    /** The page's stream ends with an ACTION_CANCEL for the pointers it had (a claim, a reset). */
    private fun cancelPage(cancel: PenRouter.Forward) {
        val last = lastGiven ?: return
        lastGiven = null
        val out = rebuild(last, cancel, SystemClock.uptimeMillis())
        last.recycle()
        if (out == null) return
        page?.let { deliver(it, out) }
        out.recycle()
    }

    private fun forgetGiven() {
        lastGiven?.recycle()
        lastGiven = null
    }

    /**
     * `source`'s pointers `forward.ids` as an event of `forward.action` at
     * `time` (the history with them, for a move), each eraser pointer's tool
     * the eraser. Its coordinates keep their place on screen: the event is
     * made with the source's raw ones, and moved back to the view's.
     */
    private fun rebuild(source: MotionEvent, forward: PenRouter.Forward, time: Long): MotionEvent? {
        val n = forward.ids.size
        val index = IntArray(n) { source.findPointerIndex(forward.ids[it]) }
        if (n == 0 || index.any { it < 0 }) return null
        val properties = Array(n) { k ->
            MotionEvent.PointerProperties().also {
                source.getPointerProperties(index[k], it)
                if (forward.erasers[k]) it.toolType = MotionEvent.TOOL_TYPE_ERASER
            }
        }
        val rawX = source.rawX - source.x
        val rawY = source.rawY - source.y
        val coords = Array(n) { MotionEvent.PointerCoords() }
        fun fill(h: Int) {
            for (k in 0 until n) {
                if (h == CURRENT) source.getPointerCoords(index[k], coords[k]) else source.getHistoricalPointerCoords(index[k], h, coords[k])
                coords[k].x += rawX
                coords[k].y += rawY
            }
        }
        val history = if (forward.action == MotionEvent.ACTION_MOVE) source.historySize else 0
        fill(if (history > 0) 0 else CURRENT)
        val action = when (forward.action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                forward.action or (forward.index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> forward.action
        }
        val flags = if (forward.action == MotionEvent.ACTION_CANCEL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.flags or MotionEvent.FLAG_CANCELED
        } else {
            source.flags
        }
        val out = MotionEvent.obtain(
            streamDown, if (history > 0) source.getHistoricalEventTime(0) else time, action, n, properties, coords,
            source.metaState, source.buttonState, source.xPrecision, source.yPrecision, source.deviceId, source.edgeFlags,
            source.source, flags,
        )
        for (h in 1 until history) {
            fill(h)
            out.addBatch(source.getHistoricalEventTime(h), coords, source.metaState)
        }
        if (history > 0) {
            fill(CURRENT)
            out.addBatch(time, coords, source.metaState)
        }
        out.offsetLocation(-rawX, -rawY)
        return out
    }

    /** To the page, in its own coordinates, as a ViewGroup hands a child its events. */
    private fun deliver(page: View, event: MotionEvent) {
        val dx = (scrollX - page.left).toFloat()
        val dy = (scrollY - page.top).toFloat()
        event.offsetLocation(dx, dy)
        page.dispatchTouchEvent(event)
        event.offsetLocation(-dx, -dy)
    }

    private fun axes(event: MotionEvent): Axes = axes.getOrPut(event.deviceId) {
        val device = event.device
        fun range(axis: Int) = device?.getMotionRange(axis, event.source) ?: device?.getMotionRange(axis)
        // A device without a pressure sensor often reports a constant 1: no range, or an empty one.
        Axes(pressure = (range(MotionEvent.AXIS_PRESSURE)?.range ?: 0f) > 0f, tilt = range(MotionEvent.AXIS_TILT) != null)
    }

    private fun axis(event: MotionEvent, axis: Int, i: Int, h: Int): Double =
        (if (h == CURRENT) event.getAxisValue(axis, i) else event.getHistoricalAxisValue(axis, i, h)).toDouble()

    /** The event's time (or its history's `h`), in seconds, on the uptime clock the timer runs on. */
    private fun time(event: MotionEvent, h: Int): Double = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            (if (h == CURRENT) event.eventTimeNanos else event.getHistoricalEventTimeNanos(h)) / 1e9
        else -> (if (h == CURRENT) event.eventTime else event.getHistoricalEventTime(h)) / 1000.0
    }

    private fun now(): Double = SystemClock.uptimeMillis() / 1000.0

    /** A lift the system cancelled (API 33's FLAG_CANCELED: a palm, an accidental touch), which is no lift. */
    private fun canceled(event: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && event.flags and MotionEvent.FLAG_CANCELED != 0

    /** What the capture asks for: the live line, the shell's messages, the hold's timer. */
    private inner class Output : PenCapture.Output {
        override fun preview(samples: List<DoubleArray>, predicted: List<DoubleArray>, shape: HeldShape?, tool: InkTool) =
            live.show(samples, predicted, shape, tool)

        override fun stroke(pointerId: Int, samples: List<DoubleArray>, held: Int?) {
            val line = live.finish()
            val listener = listener
            if (listener != null) listener.onStroke(pointerId, samples, held, line) else line?.let(live::release)
        }

        override fun abandon(pointerId: Int) {
            live.cancel()
            listener?.onAbandon(pointerId)
        }

        override fun hold(pointerId: Int, samples: List<DoubleArray>) {
            listener?.onHold(pointerId, samples)
        }

        override fun drop() = live.cancel()

        override fun holdAt(time: Double?) {
            timer.removeCallbacksAndMessages(HOLD)
            if (time != null) timer.postAtTime(fire, HOLD, ceil(time * 1000).toLong())
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** A history position meaning the event's own sample. */
        const val CURRENT = -1
        /** The hold timer's messages on the main looper. */
        val HOLD = Any()
    }
}
