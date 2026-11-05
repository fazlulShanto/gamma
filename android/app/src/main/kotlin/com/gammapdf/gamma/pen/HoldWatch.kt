package com.gammapdf.gamma.pen

/**
 * When a captured stroke asks the page about a hold (docs/dev/ipad.md "The
 * pen", step 6), ported from ipad/GammaIPad/Pencil/PencilCapture.swift
 * HoldWatch. Pure: the time is the caller's, on the events' clock in seconds,
 * so the timer's rules are tested without a looper. The hold is due `delay`
 * after the stillness anchor's sample (StrokeRecorder); one is asked at a time;
 * after an answer of nothing the next one waits for the anchor to move; after
 * a shape or a cancel the stroke asks no more; and an answer nobody is waiting
 * for changes nothing.
 */
class HoldWatch(val delay: Double = 0.6) {
    /**
     * A hold sent: the stillness anchor it was asked for, the number of
     * samples it carried, the last of them (where the pen stood) and the
     * snap's time on the samples' clock, the anchor's t + the delay, in ms.
     */
    data class Ask(val anchor: Int, val n: Int, val fromX: Double, val fromY: Double, val tSnap: Double)

    /** The hold asked and not answered yet. */
    var pending: Ask? = null
        private set

    /** The page answered with a shape or took the hold: no more holds. */
    var settled = false
        private set

    /** The anchor of the last hold answered with nothing. */
    private var spent: Int? = null

    /**
     * The anchor of the recorder's present still spell and when its hold is
     * due (seconds), or null when there is none to ask: one is pending, the
     * stroke has settled, this spell was answered already, or there are no
     * samples.
     */
    fun due(r: StrokeRecorder): Pair<Int, Double>? {
        if (pending != null || settled || r.size == 0 || r.stillSince == spent) return null
        return r.stillSince to r.stillTime + delay
    }

    /**
     * The timer fired at `now`: the hold to send (with exactly the recorder's
     * samples now), which then waits for its answer, or null when it is not
     * due yet (5 ms early are allowed) or there is none to ask.
     */
    fun fire(r: StrokeRecorder, now: Double): Ask? {
        val (anchor, time) = due(r) ?: return null
        if (now < time - 0.005) return null
        val samples = r.samples
        val last = samples[samples.size - 1]
        return Ask(anchor, samples.size, last[0], last[1], samples[anchor][3] + delay * 1000).also { pending = it }
    }

    /** The page's answer to the pending hold: the hold it answers, or null when none was pending. */
    fun answered(reply: HoldReply): Ask? {
        val ask = pending ?: return null
        pending = null
        when (reply) {
            is HoldReply.Shape, HoldReply.Cancel -> settled = true
            HoldReply.Nothing -> spent = ask.anchor
        }
        return ask
    }
}
