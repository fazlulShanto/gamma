package com.gammapdf.gamma.shell

import com.gammapdf.gamma.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strings on the app's two message ports (shell/HostWire.kt), as
 * assets/shell/host.js and chrome.js write and read them, and the sends that
 * wait for a page's answer.
 */
class HostWireTest {
    @Test
    fun readsWhatThePagesPost() {
        assertEquals(PortMessage.Hello, PortMessage.parse("""{"hello":true}"""))
        val call = PortMessage.parse("""{"id":3,"msg":{"type":"files"}}""") as PortMessage.Request
        assertEquals(3, (call.id as Number).toInt())
        assertEquals(mapOf("type" to "files"), call.body["msg"])
        val chrome = PortMessage.parse("""{"id":1,"method":"barExpand","args":[true]}""") as PortMessage.Request
        assertEquals("barExpand", chrome.body["method"])
        assertEquals(listOf(true), chrome.body["args"])
        assertEquals(PortMessage.Result(7, mapOf("committed" to true), null),
                     PortMessage.parse("""{"result":7,"value":{"committed":true}}"""))
        assertEquals(PortMessage.Result(8, null, "no listener"), PortMessage.parse("""{"result":8,"error":"no listener"}"""))
        assertEquals("a page that resolved with nothing", PortMessage.Result(9, null, null),
                     PortMessage.parse("""{"result":9,"value":null}"""))
    }

    @Test
    fun ignoresWhatIsNoMessage() {
        for (text in listOf("", "null", "[]", "\"hello\"", "{", """{"hello":false}""", """{"msg":{}}""",
                            """{"id":null,"msg":{}}""", """{"id":{},"msg":{}}""", """{"result":1.5}""")) {
            assertNull(text, PortMessage.parse(text))
        }
    }

    @Test
    fun writesWhatThePagesRead() {
        assertEquals("""{"reply":4,"value":{"taken":true}}""", PortMessage.reply(4, mapOf("taken" to true)))
        assertEquals("""{"reply":4,"value":null}""", PortMessage.reply(4, null))
        assertEquals("""{"reply":"x","value":"http://127.0.0.1:9100"}""", PortMessage.reply("x", "http://127.0.0.1:9100"))
        assertEquals("""{"reply":5,"error":"Gamma for Android has no openPath."}""",
                     PortMessage.refusal(5, "Gamma for Android has no openPath."))
        assertEquals("""{"send":2,"msg":{"type":"filesOpened"}}""", PortMessage.send(2, HostMessage.FilesOpened.encode()))
        assertEquals("""{"state":{"version":"1.0","busy":null}}""", PortMessage.state(linkedMapOf("version" to "1.0", "busy" to null)))
        // A stroke's samples go as arrays of numbers.
        val stroke = PortMessage.send(1, HostMessage.Stroke(7, listOf(doubleArrayOf(1.5, 2.0, 0.5, 0.0))).encode())
        assertEquals("""{"send":1,"msg":{"type":"stroke","pointerId":7,"samples":[[1.5,2,0.5,0]]}}""", stroke)
    }

    @Test
    fun anIdComesBackAsItWent() {
        val call = PortMessage.parse("""{"id":12,"msg":{}}""") as PortMessage.Request
        val reply = Json.objectOrNull(PortMessage.reply(call.id, emptyMap<String, Any?>()))!!
        assertEquals(12, (reply["reply"] as Number).toInt())
    }

    @Test
    fun aSendEndsWithThePagesAnswer() {
        val sends = PendingSends<String>()
        val heard = mutableListOf<SendAnswer>()
        val a = sends.add("doc1") { heard += it }
        val b = sends.add("doc1") { heard += it }
        val c = sends.add("doc1") { heard += it }
        assertEquals(3, sends.count)
        sends.answer(a, mapOf("committed" to true), null)
        sends.answer(b, null, null)
        sends.answer(c, null, "rejected")
        assertEquals(listOf(SendAnswer.Delivered(mapOf("committed" to true)), SendAnswer.Delivered(emptyMap<String, Any?>()),
                            SendAnswer.Failed), heard)
        sends.answer(a, null, null)
        assertEquals("an answer comes once", 3, heard.size)
        assertEquals(0, sends.count)
    }

    @Test
    fun aGoneDocumentAnswersNothing() {
        // Documents are told apart by identity, as reply proxies are.
        val doc1 = Any()
        val doc2 = Any()
        val sends = PendingSends<Any>()
        val heard = mutableMapOf<Long, SendAnswer>()
        val old = sends.add(doc1) { heard[1] = it }
        sends.keepOnly(doc1)
        assertTrue("the document that has the page keeps its sends", heard.isEmpty())
        val fresh = sends.add(doc2) { heard[2] = it }
        sends.keepOnly(doc2)
        assertEquals(SendAnswer.Failed, heard[1])
        assertNull(heard[2])
        sends.answer(old, null, null)
        assertEquals(SendAnswer.Failed, heard[1])
        sends.keepOnly(null)
        assertEquals(SendAnswer.Failed, heard[2])
        sends.answer(fresh, null, null)
        assertEquals(0, sends.count)
    }

    @Test
    fun aSendThatCouldNotBePostedFails() {
        val sends = PendingSends<String>()
        var heard: SendAnswer? = null
        val n = sends.add("doc") { heard = it }
        sends.fail(n)
        assertEquals(SendAnswer.Failed, heard)
        assertEquals(0, sends.count)
    }
}
