package com.gammapdf.gamma.server

import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The web app unpacked from the assets, which replaces the iPad's
 * testThePythonPathsInTheBundle: Chaquopy lays out Python itself, and what
 * the server needs from the build is a folder with index.html in it.
 */
class WebAssetsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val assets by lazy { folder.newFolder("assets") }
    private val files by lazy { folder.newFolder("files") }
    private var opened = 0

    /** The assets bundleWebAssets makes, with [stamp] for their contents. */
    private fun build(stamp: String, vararg web: Pair<String, String>) {
        assets.deleteRecursively()
        for ((path, text) in web) File(assets, "frontend_dist/$path").apply { parentFile!!.mkdirs() }.writeText(text)
        File(assets, "frontend_dist.files").writeText(web.joinToString("\n") { it.first } + "\n")
        File(assets, "frontend_dist.stamp").writeText("$stamp\n")
    }

    private fun webAssets() = WebAssets({ name ->
        opened++
        val file = File(assets, name)
        if (!file.isFile) throw FileNotFoundException(name)
        file.inputStream()
    }, File(files, "frontend_dist"))

    @Test
    fun theWebAppIsUnpackedWithItsIndex() {
        build("aaa", "index.html" to "<!doctype html>", "assets/app.js" to "js", "favicon.svg" to "<svg/>")
        val directory = webAssets().ensure()
        assertEquals(File(files, "frontend_dist"), directory)
        assertEquals("<!doctype html>", File(directory, "index.html").readText())
        assertEquals("js", File(directory, "assets/app.js").readText())
        assertEquals("aaa", File(files, "frontend_dist.stamp").readText().trim())
        assertFalse("the stamp is not served", File(directory, "frontend_dist.stamp").exists())
    }

    @Test
    fun anUnchangedStampCostsOnlyItsRead() {
        build("aaa", "index.html" to "one")
        webAssets().ensure()
        opened = 0
        webAssets().ensure()
        assertEquals("the stamp, and nothing else", 1, opened)
    }

    @Test
    fun aNewBuildReplacesTheWholeFolder() {
        build("aaa", "index.html" to "one", "assets/old.js" to "old")
        webAssets().ensure()
        build("bbb", "index.html" to "two", "assets/new.js" to "new")
        val directory = webAssets().ensure()
        assertEquals("two", File(directory, "index.html").readText())
        assertTrue(File(directory, "assets/new.js").isFile)
        assertFalse("nothing of the old build stays", File(directory, "assets/old.js").exists())
        assertEquals(listOf("frontend_dist", "frontend_dist.stamp"), files.list()!!.sorted())
    }

    @Test
    fun aFolderWithoutItsIndexIsUnpackedAgain() {
        build("aaa", "index.html" to "one")
        val directory = webAssets().ensure()
        File(directory, "index.html").delete()
        webAssets().ensure()
        assertEquals("one", File(directory, "index.html").readText())
    }

    @Test
    fun anUnpackingCutShortIsClearedAway() {
        build("aaa", "index.html" to "one")
        File(files, "frontend_dist.partial/stale.js").apply { parentFile!!.mkdirs() }.writeText("stale")
        webAssets().ensure()
        assertFalse(File(files, "frontend_dist.partial").exists())
        assertFalse(File(files, "frontend_dist/stale.js").exists())
    }

    @Test
    fun twoUnpackingsAtOnceLeaveOneWholeFolder() {
        build("aaa", "index.html" to "one", "assets/a.js" to "a", "assets/b.js" to "b", "assets/c.js" to "c")
        val firstPaused = CountDownLatch(1)
        val secondCopying = CountDownLatch(1)
        lateinit var first: Thread
        // The first unpacking pauses halfway through its copy, for as long as
        // the second takes to start copying, or half a second when the second
        // has to wait for it.
        val webAssets = WebAssets({ name ->
            if (Thread.currentThread() === first) {
                if (name == "frontend_dist/assets/b.js") {
                    firstPaused.countDown()
                    secondCopying.await(500, TimeUnit.MILLISECONDS)
                }
            } else if (name.startsWith("frontend_dist/")) {
                secondCopying.countDown()
            }
            File(assets, name).inputStream()
        }, File(files, "frontend_dist"))
        val failures = ConcurrentLinkedQueue<Throwable>()
        first = thread(start = false) { runCatching { webAssets.ensure() }.onFailure { failures += it } }
        val second = thread(start = false) { runCatching { webAssets.ensure() }.onFailure { failures += it } }
        first.start()
        assertTrue("the first unpacking reached its pause", firstPaused.await(10, TimeUnit.SECONDS))
        second.start()
        first.join(10_000)
        second.join(10_000)
        assertEquals(emptyList<Throwable>(), failures.toList())
        val directory = File(files, "frontend_dist")
        for ((path, text) in listOf("index.html" to "one", "assets/a.js" to "a", "assets/b.js" to "b", "assets/c.js" to "c")) {
            assertEquals(path, text, File(directory, path).readText())
        }
        assertEquals("aaa", File(files, "frontend_dist.stamp").readText().trim())
        assertEquals(listOf("frontend_dist", "frontend_dist.stamp"), files.list()!!.sorted())
    }

    @Test
    fun aBuildWithoutTheWebAppIsMissing() {
        assertThrows(WebAssets.Missing::class.java) { webAssets().ensure() }
        build("aaa", "app.js" to "no index")
        assertThrows(WebAssets.Missing::class.java) { webAssets().ensure() }
        build("aaa", "index.html" to "one")
        File(assets, "frontend_dist/index.html").delete()
        assertThrows("a listed file that is not there", WebAssets.Missing::class.java) { webAssets().ensure() }
        assertFalse(File(files, "frontend_dist").exists())
    }

    @Test
    fun aListedPathOutsideTheFolderIsRefused() {
        build("aaa", "index.html" to "one")
        File(assets, "frontend_dist.files").writeText("index.html\n../escape.txt\n")
        assertThrows(WebAssets.Missing::class.java) { webAssets().ensure() }
        assertFalse(File(files, "escape.txt").exists())
    }
}
