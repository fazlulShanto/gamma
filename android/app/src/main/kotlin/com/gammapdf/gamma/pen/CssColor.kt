package com.gammapdf.gamma.pen

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A CSS hex colour, as the web app writes tool colours and its theme's
 * background: "#rgb", "#rrggbb" or "#rrggbbaa", ported from
 * ipad/GammaIPad/Pencil/LiveStrokeView.swift CSSColor. As an Android colour
 * int (ARGB), so not through Color.parseColor, which reads eight digits as
 * #aarrggbb where CSS puts the alpha last.
 */
object CssColor {
    /** The colour `text` names, or null when it is no hex colour. Spaces and tabs around it are ignored. */
    fun parse(text: String?): Int? {
        val trimmed = text?.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() } ?: return null
        if (!trimmed.startsWith("#")) return null
        var hex = trimmed.substring(1)
        if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
        if ((hex.length != 6 && hex.length != 8) || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = hex.toLong(16)
        return if (hex.length == 6) (0xff shl 24) or value.toInt() else ((value and 0xff) shl 24 or (value ushr 8)).toInt()
    }

    /** `argb` with its alpha replaced by `alpha`, held to 0…1 (NaN as 0), as UIColor.withAlphaComponent does. */
    fun withAlpha(argb: Int, alpha: Double): Int {
        val a = if (alpha > 0) min(alpha, 1.0) else 0.0
        return ((a * 255).roundToInt() shl 24) or (argb and 0xffffff)
    }
}
