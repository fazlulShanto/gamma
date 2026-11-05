package com.gammapdf.gamma.pen

/**
 * The page's answer to `hold`, the value its promise resolved with as decoded
 * JSON, ported from ipad/GammaIPad/Shell/Bridge.swift HoldReply:
 * `{shape: {kind, params}}` to snap to that shape (HeldShape, viewport CSS
 * px), `{cancel: true}` when the page took the hold (*Paste here*), and
 * anything else, `{}` included, nothing. A cancel wins over a shape; a shape of
 * an unknown kind, or with params it cannot be drawn from, is nothing.
 */
sealed interface HoldReply {
    data class Shape(val shape: HeldShape) : HoldReply
    data object Cancel : HoldReply
    data object Nothing : HoldReply

    companion object {
        fun from(value: Any?): HoldReply {
            val reply = value as? Map<*, *> ?: return Nothing
            if (reply["cancel"] == true) return Cancel
            return HeldShape.decode(reply["shape"])?.let(::Shape) ?: Nothing
        }
    }
}
