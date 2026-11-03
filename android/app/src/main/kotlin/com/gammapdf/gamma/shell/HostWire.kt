package com.gammapdf.gamma.shell

import com.gammapdf.gamma.json.Json
import com.gammapdf.gamma.json.jsonDouble

// The strings the app's two message ports carry (docs/dev/android.md "The
// bridge"): `gammaAndroidHost` between the server's page and the shell
// (assets/shell/host.js, HostBridge) and `gammaShellHost` between the chrome
// pages and the shell (assets/shell/chrome.js, ChromeViews). A WebView's port
// carries strings and pairs nothing, so each message is one JSON object, and
// a request carries the id its reply names. Pure, so the JVM tests read it.

/** One message a page posted on its port, as host.js and chrome.js write them. */
sealed interface PortMessage {
    /** The script ran in a new document: the reply proxy it came with is how the shell reaches that document. */
    data object Hello : PortMessage

    /**
     * A call that wants a reply: the page's `{id, msg}` (a WebMessage) or a
     * chrome page's `{id, method, args}`. [id] goes back as it came.
     */
    data class Request(val id: Any, val body: Map<String, Any?>) : PortMessage

    /** The page's answer to the shell's `{send, msg}`: what its receive resolved with, or why it rejected. */
    data class Result(val send: Long, val value: Any?, val error: String?) : PortMessage

    companion object {
        /** The message in [text], or null for anything else, which the shell ignores. */
        fun parse(text: String): PortMessage? {
            val m = Json.objectOrNull(text) ?: return null
            if (m["hello"] == true) return Hello
            val result = m["result"].jsonDouble()
            if (result != null) {
                if (!result.isFinite() || result != Math.rint(result)) return null
                return Result(result.toLong(), m["value"], m["error"] as? String)
            }
            val id = m["id"]
            if (id !is Number && id !is String) return null
            return Request(id, m)
        }

        /** A request's answer: `{reply, value}`. */
        fun reply(id: Any, value: Any?): String = Json.write(linkedMapOf("reply" to id, "value" to value))

        /** A request refused with the sentence its promise rejects with: `{reply, error}`. */
        fun refusal(id: Any, sentence: String): String = Json.write(linkedMapOf("reply" to id, "error" to sentence))

        /** A message for the page's `window.gammaNative.receive`, answered `{result: n}`. */
        fun send(n: Long, msg: Map<String, Any?>): String = Json.write(linkedMapOf("send" to n, "msg" to msg))

        /** The chrome's state for a chrome page's `onState` listeners. */
        fun state(state: Map<String, Any?>): String = Json.write(mapOf("state" to state))
    }
}

/** How a message the shell sent a page ended. */
sealed interface SendAnswer {
    /** The page's receive resolved: with [value], `{}` when it resolved with nothing (the iPad's `?? {}`). */
    data class Delivered(val value: Any?) : SendAnswer

    /** Not delivered, or the page rejected it. */
    data object Failed : SendAnswer
}

/**
 * The shell's messages to a page that wait for its answer, each tied to the
 * document ([D], its reply proxy) it went to. A document that is gone
 * answers nothing, so when another one has the page, or none does, what was
 * sent to it fails rather than waits. Main-thread confined.
 */
class PendingSends<D : Any> {
    private class Entry<D>(val document: D, val done: (SendAnswer) -> Unit)

    private var next = 0L
    private val waiting = LinkedHashMap<Long, Entry<D>>()

    /** How many wait. */
    val count: Int get() = waiting.size

    /** A message about to go to [document]: the number its `{send}` carries; [done] hears how it ended, once. */
    fun add(document: D, done: (SendAnswer) -> Unit): Long {
        val n = ++next
        waiting[n] = Entry(document, done)
        return n
    }

    /** The page's `{result: n}`; one for nothing waiting (answered or failed already) is ignored. */
    fun answer(n: Long, value: Any?, error: String?) {
        val entry = waiting.remove(n) ?: return
        entry.done(if (error != null) SendAnswer.Failed else SendAnswer.Delivered(value ?: emptyMap<String, Any?>()))
    }

    /** Message [n] could not be posted at all. */
    fun fail(n: Long) {
        waiting.remove(n)?.done?.invoke(SendAnswer.Failed)
    }

    /** Only [document] has the page now (null: none does): what went to any other fails. */
    fun keepOnly(document: D?) {
        val gone = waiting.filterValues { it.document !== document }
        for (n in gone.keys) waiting.remove(n)
        for (entry in gone.values) entry.done(SendAnswer.Failed)
    }
}
