package com.gammapdf.gamma.server

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * The built web app the server serves (GAMMA_STATIC_DIR), unpacked from the
 * app's assets into [directory] (filesDir/frontend_dist), since the server
 * reads plain files. The build (bundleWebAssets in android/app/build.gradle.kts)
 * puts frontend/dist under the assets' frontend_dist/, with a list of its
 * files (frontend_dist.files) and a stamp of their contents
 * (frontend_dist.stamp). A new stamp, an install or an update, unpacks
 * again into a folder of its own that is then renamed into place, so an
 * interrupted copy is never served; an unchanged one costs a read of the
 * stamp. It replaces the iPad's bundle check (LocalServer.swift start).
 * Blocking, and before the interpreter starts: gamma.app registers the
 * page routes only when the folder exists at import. One lock for the
 * process runs the unpackings one at a time, whichever instance asks, so
 * two servers starting together (the device test's beside the app's) never
 * write into the same folder at once: the second finds the first's folder
 * and stamp in place.
 */
class WebAssets(private val open: (String) -> InputStream, val directory: File) {
    /** The app's assets have no web app, or not all of it: a build that did not bundle frontend/dist. */
    class Missing(message: String) : IOException(message)

    /** Beside the folder rather than in it, where the server would serve it. */
    private val stampFile = File(directory.parentFile, "${directory.name}.stamp")

    /** [directory], with the build's web app in it. */
    fun ensure(): File = synchronized(LOCK) { ensureLocked() }

    private fun ensureLocked(): File {
        val want = text(STAMP).trim()
        if (want.isEmpty()) throw Missing("$STAMP is empty")
        if (File(directory, "index.html").isFile && stamp() == want) return directory
        val paths = text(LIST).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if ("index.html" !in paths) throw Missing("$LIST lists no index.html")
        val parent = directory.parentFile ?: throw IOException("$directory has no parent folder")
        val partial = File(parent, "${directory.name}.partial")
        val old = File(parent, "${directory.name}.old")
        // An earlier unpacking that was cut short.
        partial.deleteRecursively()
        old.deleteRecursively()
        for (path in paths) {
            if (!relative(path)) throw Missing("$LIST names $path, which is not a path inside it")
            val target = File(partial, path)
            Files.createDirectories(target.parentFile!!.toPath())
            copy("$ROOT/$path", target)
        }
        stampFile.delete()
        if (directory.exists() && !directory.renameTo(old)) throw IOException("could not move the old web app aside")
        if (!partial.renameTo(directory)) throw IOException("could not move the web app into place")
        old.deleteRecursively()
        stampFile.writeText(want + "\n")
        return directory
    }

    private fun stamp(): String? = try {
        stampFile.readText().trim()
    } catch (e: IOException) {
        null
    }

    private fun text(name: String): String = asset(name).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun copy(name: String, target: File) {
        asset(name).use { input -> FileOutputStream(target).use { input.copyTo(it, BUFFER) } }
    }

    private fun asset(name: String): InputStream = try {
        open(name)
    } catch (e: FileNotFoundException) {
        throw Missing("the app's assets have no $name")
    }

    private fun relative(path: String): Boolean =
        !path.startsWith("/") && '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    companion object {
        const val ROOT = "frontend_dist"
        const val LIST = "frontend_dist.files"
        const val STAMP = "frontend_dist.stamp"
        private const val BUFFER = 64 * 1024
        private val LOCK = Any()
    }
}
