package com.gammapdf.gamma.pen

/**
 * Stylus strokes, taken natively once the page claims them (docs/dev/android.md
 * "The pen"), ported from ipad/GammaIPad/Pencil/PencilCapture.swift without
 * its UIKit recognizer: PenLayer records here every stylus stroke the page is
 * given, under its pointer's token (PenRouter), and does what Output asks.
 *
 * A stroke is recorded from its first sample while the page's ink layer
 * decides what the touch is. When the layer posts `claim` (a pen or
 * highlighter stroke, not a tap, the eraser or the lasso), the stroke that
 * went down where the page's pointer did is taken (ClaimQueue) if it is still
 * down and no other is captured, its line is drawn at once from the samples
 * so far, and its samples go back to the page at the lift. A stroke that
 * ended before its claim came is the page's to commit, as in a browser.
 *
 * The one thing decided here about the pen is that it held still: the page
 * sees nothing of a captured stroke before its lift, so the page is asked
 * (`hold`) HoldWatch's delay after the stillness anchor's sample, by a timer
 * the caller keeps (Output.holdAt, then `fire`), and its answer comes back
 * through `held`. Pure: times are seconds on the events' clock.
 */
class PenCapture(private val out: Output) {
    /** What the capture asks of PenLayer. */
    interface Output {
        /**
         * The captured stroke's line: its samples, the predicted points after
         * them (`[x, y]`), the shape the page snapped it to, drawn instead,
         * and its tool.
         */
        fun preview(samples: List<DoubleArray>, predicted: List<DoubleArray>, shape: HeldShape?, tool: InkTool)

        /**
         * The captured stroke ended: its samples (none when the page took the
         * hold) and the `n` of the hold whose shape its line was drawn as (null:
         * none was), the one hold the page may commit a shape for.
         */
        fun stroke(pointerId: Int, samples: List<DoubleArray>, held: Int?)

        /** The captured stroke was cancelled: its line goes, and the page's claim must still end. */
        fun abandon(pointerId: Int)

        /** The captured stylus held still: ask the page (`hold`) with these samples, and hand its answer to `held`. */
        fun hold(pointerId: Int, samples: List<DoubleArray>)

        /** The page took the hold (*Paste here*): the line goes now, and the stroke stays captured to its lift. */
        fun drop()

        /** Call `fire` at `time` (seconds, the events' clock) instead of any time set before; null: at no time. */
        fun holdAt(time: Double?)
    }

    private class Capture(val key: Long, val pointerId: Int, val tool: InkTool) {
        val watch = HoldWatch()
        /** The stillness anchor the hold's timer is set for (null: not set). */
        var armed: Int? = null
        /** The shape the page snapped the stroke to, as it follows the pen. */
        var shape: ShapeHold? = null
        /** The page took the hold: no line, and the lift hands back no samples. */
        var dropped = false
    }

    private val claims = ClaimQueue<Long>()
    private val recordings = HashMap<Long, StrokeRecorder>()
    private var capture: Capture? = null

    /** The captured stroke's token, or null. */
    val captured: Long? get() = capture?.key

    /**
     * The captured stroke's line runs ahead of the pen: its tool does not
     * smooth (the lazy brush trails the pen) or follow the ruler, and the page
     * has not snapped it to a shape, which is drawn as it is.
     */
    val predicts: Boolean
        get() = capture?.let { !it.dropped && it.shape == null && it.tool.stabilize == 0 && it.tool.snap == null } ?: false

    /**
     * A sample of the stroke recorded under `key`, in viewport CSS px and
     * seconds, with the pen's altitude and azimuth in radians when it reports
     * them (StrokeRecorder.add). A key's first sample is its down: the stroke
     * can be claimed from then on.
     */
    fun record(
        key: Long, x: Double, y: Double, force: Double, maxForce: Double, time: Double,
        altitude: Double? = null, azimuth: Double? = null,
    ) {
        recordings[key]?.let {
            it.add(x, y, force, maxForce, time, altitude, azimuth)
            return
        }
        recordings[key] = StrokeRecorder().apply { add(x, y, force, maxForce, time, altitude, azimuth) }
        claims.began(key, x, y, time)
    }

    /** An event's samples of `key` are recorded: its line follows, ahead by `predicted`, and the hold waits for the pen again. */
    fun moved(key: Long, predicted: List<DoubleArray> = emptyList()) {
        if (capture?.key != key) return
        preview(predicted)
        armHold()
    }

    /**
     * The stroke under `key` lifted, its last sample recorded. The captured
     * one's line is drawn once more from exactly the samples handed back,
     * without prediction, so a held shape is the one the page commits (its
     * followHold over these samples), for the hold named; a lift that came
     * before the page answered a hold has no shape, and names none. Any other
     * stroke is over: a claim for it now comes too late.
     */
    fun lifted(key: Long, time: Double) {
        val recorder = recordings.remove(key) ?: return
        val c = capture
        if (c == null || c.key != key) {
            claims.ended(key, time)
            return
        }
        capture = null
        out.holdAt(null)
        val samples = recorder.samples
        if (!c.dropped) out.preview(samples, emptyList(), c.shape?.current(samples), c.tool)
        out.stroke(c.pointerId, if (c.dropped) emptyList() else samples, if (c.dropped) null else c.shape?.n)
    }

    /** The stroke under `key` was cancelled by the system: nothing to hand back. */
    fun cancelled(key: Long, time: Double) {
        recordings.remove(key) ?: return
        val c = capture
        if (c == null || c.key != key) {
            claims.ended(key, time)
            return
        }
        capture = null
        out.holdAt(null)
        out.abandon(c.pointerId)
    }

    /**
     * The page's claim for its `pointerId`, which went down at (x, y), at
     * `now`: the token of the stroke taken, or null when none is (one is
     * captured already, none went down there, or it has ended).
     */
    fun claim(pointerId: Int, x: Double, y: Double, tool: InkTool, now: Double): Long? {
        if (capture != null) return null
        val key = claims.claim(x, y, now) ?: return null
        if (key !in recordings) return null
        capture = Capture(key, pointerId, tool)
        preview(emptyList())
        armHold()
        return key
    }

    /**
     * The page's answer to the hold asked for `pointerId`. A shape is drawn at
     * once (the pen is still: no event will come to draw it) and follows the
     * pen from then on; a cancel takes the line away; nothing lets the next
     * still spell ask again. An answer for a stroke that has ended, or that
     * asked nothing, changes nothing: that lift sent the freehand line and
     * named no hold, which the page commits as it is.
     */
    fun held(pointerId: Int, reply: HoldReply) {
        val c = capture ?: return
        if (c.pointerId != pointerId) return
        val ask = c.watch.answered(reply) ?: return
        when (reply) {
            is HoldReply.Shape -> c.shape = ShapeHold(reply.shape, ask.fromX, ask.fromY, ask.n, ask.tSnap)
            HoldReply.Cancel -> c.dropped = true
            HoldReply.Nothing -> {}
        }
        if (c.dropped) out.drop() else preview(emptyList())
        armHold()
    }

    /** The hold's timer fired at `now`: the page is asked, or the timer is set again when it came early. */
    fun fire(now: Double) {
        val c = capture ?: return
        val recorder = recordings[c.key] ?: return
        c.armed = null
        if (c.watch.fire(recorder, now) != null) out.hold(c.pointerId, recorder.samples) else armHold()
    }

    /** Everything recorded is forgotten (the captured stroke abandoned), and nothing begun so far can be claimed. */
    fun reset(now: Double) {
        out.holdAt(null)
        recordings.clear()
        capture?.let {
            capture = null
            out.abandon(it.pointerId)
        }
        claims.endAll(now)
    }

    private fun preview(predicted: List<DoubleArray>) {
        val c = capture ?: return
        if (c.dropped) return
        val recorder = recordings[c.key] ?: return
        val samples = recorder.samples
        out.preview(samples, predicted, c.shape?.current(samples), c.tool)
    }

    /**
     * Sets the hold's timer for the captured stroke's still spell
     * (HoldWatch.due), or stops it when there is none to ask; it is moved only
     * when the anchor it is set for changes.
     */
    private fun armHold() {
        val c = capture ?: return
        val next = recordings[c.key]?.let { c.watch.due(it) }
        if (next?.first == c.armed) return
        c.armed = next?.first
        out.holdAt(next?.second)
    }
}
