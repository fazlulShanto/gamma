package com.gammapdf.gamma.pen

import kotlin.math.hypot

/**
 * Which stylus touch a claim is for, ported from
 * ipad/GammaIPad/Pencil/PencilCapture.swift ClaimQueue. The web names its
 * pointer, which the native side cannot see, so a claim says where its pointer
 * went down and takes the touch that began there: the oldest one within
 * `reach` CSS px of that place, so two touches at one spot still go in order.
 * A claim whose touch already ended (a dot, finished by the page's own
 * `pointerup` before the claim arrived) takes nothing, and an ended touch is
 * remembered for `grace` seconds so a late claim cannot grab the next touch
 * begun at the same spot instead. Matching by place keeps a miss local: a
 * touch nobody claimed (a tap on a button, the lasso) only stands in the way
 * of a claim for that same spot, and not of every stroke written after it.
 * Times are seconds on the events' clock.
 */
class ClaimQueue<K>(val grace: Double = 0.5, val reach: Double = 10.0) {
    private class Entry<K>(val key: K, val x: Double, val y: Double) {
        var ended: Double? = null
    }

    private val entries = ArrayList<Entry<K>>()

    val count: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    /** A stylus touch went down at (x, y). Touches that ended long enough ago go now. */
    fun began(key: K, x: Double, y: Double, time: Double) {
        forget(time)
        entries.add(Entry(key, x, y))
    }

    /** Only a touch still down can end: a key can be reused by a later touch once the first is gone. */
    fun ended(key: K, time: Double) {
        entries.firstOrNull { it.key == key && it.ended == null }?.ended = time
    }

    fun endAll(time: Double) {
        for (entry in entries) if (entry.ended == null) entry.ended = time
    }

    /** The touch a claim for a pointer down at (x, y), arriving at `now`, takes, or null. */
    fun claim(x: Double, y: Double, now: Double): K? {
        forget(now)
        val i = entries.indexOfFirst { hypot(it.x - x, it.y - y) <= reach }
        if (i < 0) return null
        val entry = entries.removeAt(i)
        return if (entry.ended == null) entry.key else null
    }

    private fun forget(now: Double) {
        entries.removeAll { entry -> entry.ended?.let { now - it > grace } ?: false }
    }
}
