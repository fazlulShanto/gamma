package com.gammapdf.gamma.pen

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/** What a claimed stroke is drawn as: anything but a highlighter draws as a pen. */
enum class InkKind { PEN, HIGHLIGHTER }

/** A pen's brush (finishStroke.js BRUSHES; InkWidth): absent, or one the shell does not know, is the plain Pen. */
enum class Brush {
    PEN, MONOLINE, BRUSH, PENCIL, CALLIGRAPHY;

    companion object {
        /** The brush a claim names ("monoline", …), or null for a name this build does not know. */
        fun named(name: String): Brush? = entries.firstOrNull { it.name.lowercase() == name }
    }
}

/**
 * The ruler's edge a stroke runs along (RulerSnap): a point of it, (x0, y0) in
 * viewport CSS px, and its angle in degrees counterclockwise as seen, the edge
 * running along (cos angle, −sin angle).
 */
data class Snap(val x0: Double, val y0: Double, val angle: Double)

/**
 * The tool a claimed stroke draws with, as the web's ink layer has it, in its
 * preset's look (frontend/src/ink/hostInk.js claimTool; docs/dev/ipad.md "The
 * host contract"), ported from ipad/GammaIPad/Shell/Bridge.swift InkTool.
 * `kind`, `color` and `size` are required. Each look key is left out at its
 * default and read leniently: a value the shell cannot read is dropped, never
 * the claim, so a newer page's claim still draws here, as its plain line at
 * the least. `size` is the line's width on screen in CSS px; `dash` is
 * `[on, off]` in CSS px; `stabilize` the lazy brush's level, 0…4; `nib` a
 * calligraphy pen's angle, degrees 0…179 counterclockwise as seen (the edge the
 * vector (cos nib, −sin nib) in y-down space), and `nibRoll` whether it turns
 * with the pen's barrel roll, which no Android stylus reports.
 */
data class InkTool(
    val kind: InkKind,
    val color: String,
    val size: Double,
    val opacity: Double = 1.0,
    val brush: Brush = Brush.PEN,
    val pressure: Boolean = true,
    val dash: List<Double>? = null,
    val multiply: Boolean = false,
    val stabilize: Int = 0,
    val snap: Snap? = null,
    val nib: Int = 45,
    val nibRoll: Boolean = false,
) {
    /**
     * The nib the live line draws with: the claim's, turned by `roll` (the
     * stroke's first sample's, degrees clockwise as seen) when it follows the
     * roll, (nib − roll) mod 180, the page's strokeNib. Android reports no
     * roll, so there it is always the claim's nib.
     */
    fun liveNib(roll: Double?): Int {
        if (!nibRoll || roll == null || !roll.isFinite()) return nib
        return mod(roundHalfAway(nib - roll), 180.0).toInt()
    }

    companion object {
        /**
         * The `tool` of a `claim`, a decoded JSON object. Throws
         * IllegalArgumentException when `kind` or `color` is not a string,
         * `size` is not a number, or `opacity` is present and not a number:
         * the claim is then refused, as the iPad's decoder refuses it.
         */
        fun decode(m: Map<String, Any?>): InkTool {
            val kind = m["kind"] as? String ?: throw IllegalArgumentException("a tool without its kind")
            val color = m["color"] as? String ?: throw IllegalArgumentException("a tool without its colour")
            val size = (m["size"] as? Number)?.toDouble() ?: throw IllegalArgumentException("a tool without its size")
            val opacity = when (val o = m["opacity"]) {
                null -> 1.0
                is Number -> o.toDouble()
                else -> throw IllegalArgumentException("an opacity that is no number")
            }
            // A dash is two lengths, both finite and above 0.
            val dash = (m["dash"] as? List<*>)
                ?.takeIf { pattern -> pattern.size == 2 && pattern.all { it is Number && it.toDouble().isFinite() && it.toDouble() > 0 } }
                ?.map { (it as Number).toDouble() }
            // A level is a number, rounded and held to 0…4.
            val level = (m["stabilize"] as? Number)?.toDouble()?.takeIf { it.isFinite() }
                ?.let { roundHalfAway(it).coerceIn(0.0, 4.0).toInt() }
            // A nib is a number, rounded and turned into 0…179 on the Int, where % and + 180 are exact.
            val nib = (m["nib"] as? Number)?.toDouble()?.takeIf { it.isFinite() && abs(it) < 1e6 }
                ?.let { val degrees = roundHalfAway(it).toInt() % 180; if (degrees < 0) degrees + 180 else degrees }
            return InkTool(
                kind = if (kind == "highlighter") InkKind.HIGHLIGHTER else InkKind.PEN,
                color = color,
                size = size,
                opacity = opacity,
                brush = (m["brush"] as? String)?.let(Brush::named) ?: Brush.PEN,
                pressure = m["pressure"] as? Boolean ?: true,
                dash = dash,
                multiply = m["blend"] == "multiply",
                stabilize = level ?: 0,
                snap = snap(m["snap"]),
                nib = nib ?: 45,
                nibRoll = m["nibRoll"] as? Boolean ?: false,
            )
        }

        /** `{x0, y0, angle}`, all numbers, or null: a snap the shell cannot read is dropped, never the claim. */
        private fun snap(v: Any?): Snap? {
            val m = v as? Map<*, *> ?: return null
            val x0 = (m["x0"] as? Number)?.toDouble() ?: return null
            val y0 = (m["y0"] as? Number)?.toDouble() ?: return null
            val angle = (m["angle"] as? Number)?.toDouble() ?: return null
            return Snap(x0, y0, angle)
        }

        /** Swift's `rounded()`: the nearest whole number, halves away from zero. */
        private fun roundHalfAway(v: Double): Double {
            val whole = truncate(v)
            return if (abs(v - whole) >= 0.5) whole + sign(v) else whole
        }
    }
}
