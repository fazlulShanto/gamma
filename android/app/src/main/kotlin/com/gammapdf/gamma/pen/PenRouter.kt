package com.gammapdf.gamma.pen

/**
 * What the page gets of each touch event, and which stylus pointers are
 * recorded (docs/dev/android.md "The pen"): PenLayer's rules, the Android
 * counterpart of the states of ipad/GammaIPad/Pencil/PencilCapture.swift's
 * recognizer, kept pure so they are tested on the JVM (MotionEvent's values
 * are written out below).
 *
 * Android sends every pointer of a gesture in one stream, and a view's stream
 * is cancelled whole, so the page's stream is made of the pointers it is
 * given. Until a claim that is every pointer, and each event goes to the page
 * as it came, except that a stylus that touches down with its primary button
 * held goes as the eraser for the rest of its stroke (the page erases with it
 * and never claims it, as it does the barrel in a browser). A claim taken
 * (`take`) ends the page's stream with one ACTION_CANCEL, and the rest of the
 * gesture is swallowed, fingers too (the palm), so the page cannot move under
 * the pen; once the captured stylus is up, a stylus that touches down is the
 * page's again, in a stream of its own that starts with ACTION_DOWN, and can
 * be claimed in turn. A gesture is one device's, from its ACTION_DOWN to its
 * ACTION_UP or ACTION_CANCEL; another device's events in between belong to no
 * gesture here and are dropped.
 *
 * Every stylus pointer the page is given is recorded from its down to its up,
 * under a token of its own: a pointer id is used again by a later touch, a
 * token never is.
 */
class PenRouter {
    /**
     * What the page gets for one event: `action` (masked: ACTION_DOWN,
     * ACTION_MOVE, …) for the pointers `ids`, in the event's order, the
     * action's own pointer at `index` among them, each pointer whose entry in
     * `erasers` is true as the eraser. `whole`: that is the event as it came,
     * the page's stream being the gesture's. `fresh`: the page's stream starts
     * here with an ACTION_DOWN, though the gesture began before.
     */
    class Forward(
        val action: Int,
        val ids: IntArray,
        val index: Int,
        val erasers: BooleanArray,
        val whole: Boolean,
        val fresh: Boolean,
    )

    /**
     * One event's routing: what the page gets (null: nothing), each of the
     * event's pointers' recording token in the event's order (0: not
     * recorded), and whether the event is the gesture's at all.
     */
    class Route(val forward: Forward?, val tokens: LongArray, val accepted: Boolean)

    /** A pointer of the gesture: given to the page as the eraser, and its token (0: not recorded). */
    private class Pointer(val eraser: Boolean, var token: Long)

    private val pointers = HashMap<Int, Pointer>()
    /** The pointers the page's stream has, in the order they joined it. */
    private val given = LinkedHashSet<Int>()
    private var device = 0
    /** The captured stylus's pointer id, while it is down. */
    private var captured: Int? = null
    private var nextToken = 1L

    /** The page's stream was ended within this gesture (`take`, `abort`): the rest is not the page's. */
    var swallowing = false
        private set

    /** A gesture is under way: a pointer is down. */
    val active: Boolean get() = pointers.isNotEmpty()

    /**
     * One touch event: its masked action, the id of the pointer the action is
     * about, every pointer's id and tool type in the event's order, its button
     * state, whether it carries FLAG_CANCELED (a lift that is a cancel, the
     * system's palm rejection), and its device.
     */
    fun route(
        action: Int, actionId: Int, ids: IntArray, tools: IntArray, buttonState: Int, canceled: Boolean, deviceId: Int,
    ): Route {
        if (action == ACTION_DOWN) {
            clear()
            device = deviceId
        } else if (pointers.isEmpty() || deviceId != device) {
            return Route(null, LongArray(ids.size), accepted = false)
        }
        val before = ids.filter { it in given }.toIntArray()
        if (action == ACTION_DOWN || action == ACTION_POINTER_DOWN) {
            val tool = tools.getOrElse(ids.indexOf(actionId)) { TOOL_TYPE_UNKNOWN }
            val pen = tool == TOOL_TYPE_STYLUS || tool == TOOL_TYPE_ERASER
            val gives = !swallowing || (pen && captured == null)
            val eraser = tool == TOOL_TYPE_STYLUS && buttonState and BUTTON_STYLUS_PRIMARY != 0
            val token = if (gives && tool == TOOL_TYPE_STYLUS && !eraser) nextToken++ else 0L
            pointers[actionId] = Pointer(eraser, token)
            if (gives) given.add(actionId)
        }
        val tokens = LongArray(ids.size) { pointers[ids[it]]?.token ?: 0L }
        val forward = forward(action, actionId, ids, before, canceled)
        when (action) {
            ACTION_UP, ACTION_CANCEL -> clear()
            ACTION_POINTER_UP -> {
                pointers.remove(actionId)
                given.remove(actionId)
                if (captured == actionId) captured = null
            }
        }
        return Route(forward, tokens, accepted = true)
    }

    /**
     * A claim took the stylus recorded under `token`: the page's stream ends
     * (the ACTION_CANCEL to send it, null when it had no pointers) and the
     * rest of the gesture is swallowed.
     */
    fun take(token: Long): Forward? {
        val id = pointers.entries.firstOrNull { it.value.token == token }?.key ?: return null
        captured = id
        swallowing = true
        return endStream()
    }

    /**
     * Everything recorded is forgotten and the page's stream ends (the
     * ACTION_CANCEL to send it, or null): what is left of the gesture is
     * swallowed, but for a stylus that touches down.
     */
    fun abort(): Forward? {
        for (pointer in pointers.values) pointer.token = 0L
        captured = null
        swallowing = pointers.isNotEmpty()
        return endStream()
    }

    private fun endStream(): Forward? {
        if (given.isEmpty()) return null
        val ids = given.toIntArray()
        given.clear()
        return Forward(ACTION_CANCEL, ids, 0, erasers(ids), whole = false, fresh = false)
    }

    private fun clear() {
        pointers.clear()
        given.clear()
        captured = null
        swallowing = false
    }

    private fun forward(action: Int, actionId: Int, ids: IntArray, before: IntArray, canceled: Boolean): Forward? {
        fun make(to: Int, part: IntArray, index: Int, fresh: Boolean = false) = Forward(
            to, part, index, erasers(part),
            whole = !swallowing && to == action && part.size == ids.size, fresh = fresh,
        )
        return when (action) {
            ACTION_DOWN, ACTION_POINTER_DOWN -> {
                if (actionId !in given) return null
                val after = ids.filter { it in given }.toIntArray()
                if (after.size == 1) {
                    make(ACTION_DOWN, after, 0, fresh = action != ACTION_DOWN)
                } else {
                    make(ACTION_POINTER_DOWN, after, after.indexOf(actionId))
                }
            }
            ACTION_MOVE -> if (before.isEmpty()) null else make(ACTION_MOVE, before, 0)
            // A lift with FLAG_CANCELED is the system's cancel of that pointer: the page's stream
            // is cancelled with it when it was the stream's last.
            ACTION_UP, ACTION_POINTER_UP -> when {
                actionId !in before -> null
                before.size == 1 -> make(if (canceled) ACTION_CANCEL else ACTION_UP, before, 0)
                else -> make(ACTION_POINTER_UP, before, before.indexOf(actionId))
            }
            ACTION_CANCEL -> if (before.isEmpty()) null else make(ACTION_CANCEL, before, 0)
            else -> if (!swallowing && before.size == ids.size) make(action, before, 0) else null
        }
    }

    private fun erasers(ids: IntArray) = BooleanArray(ids.size) { pointers[ids[it]]?.eraser == true }

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val TOOL_TYPE_UNKNOWN = 0
        const val TOOL_TYPE_STYLUS = 2
        const val TOOL_TYPE_ERASER = 4
        const val BUTTON_STYLUS_PRIMARY = 0x20

        /** A stylus's tool: its tip or its eraser. */
        fun isStylus(tool: Int): Boolean = tool == TOOL_TYPE_STYLUS || tool == TOOL_TYPE_ERASER
    }
}
