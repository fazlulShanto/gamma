package com.gammapdf.gamma.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** ipad/GammaIPadTests/ServerTests.swift ServerStateTests. */
class ServerStateTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theSupervisorsFileIsReadAndAnythingElseIsNone() {
        val file = File(folder.root, "server.state")
        assertNull("no file yet", ServerState.read(file))
        file.writeText("""{"state": "stopped", "detail": "the server could not listen on its port", "at": "2026-10-16T10:00:00+00:00"}""")
        assertEquals(ServerState("stopped", "the server could not listen on its port"), ServerState.read(file))
        file.writeText("""{"state": "start""")
        assertNull("half a write (the supervisor renames a whole file into place, so this is not expected)", ServerState.read(file))
        file.writeText("""{"state": "starting"}""")
        assertNull("a detail is always written", ServerState.read(file))
    }
}
