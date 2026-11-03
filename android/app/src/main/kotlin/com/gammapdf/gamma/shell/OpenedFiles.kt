package com.gammapdf.gamma.shell

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Base64
import java.util.UUID

/**
 * The files opened in the app (a VIEW or SEND intent: Files' Open with, a
 * share sheet's Gamma), held until the web app takes them, as the iPad holds
 * them (ipad/GammaIPad/Shell/OpenedFiles.swift; docs/dev/android.md "Opened
 * files"). Each is copied into a folder of its own and listed; the page asks
 * for the list (`files`), reads each file in chunks (`fileData`) and says
 * when it is done with one (`fileDone`), and only then does the copy go. So
 * a file opened before the page is up, signed in or on its workspace waits
 * for it, across reloads too. The page decides what a file becomes; nothing
 * here knows. The source is only read: a content URI's file stays where it is.
 *
 * Safe to use from any thread. [take] copies, so it runs off the main
 * thread; [chunk] reads a file's part.
 */
class OpenedFiles(
    /** Where the copies are, one folder per file so its name is kept (cacheDir/Opened). */
    val folder: File,
) {
    class Entry(
        val id: String,
        val name: String,
        val size: Long,
        /** The MIME type it came with, "" when none. */
        val type: String,
        val file: File,
    )

    private val held = ArrayList<Entry>()

    /** A file was added, on the thread that took it: the shell tells the page (`filesOpened`). */
    @Volatile var onArrival: (() -> Unit)? = null

    init {
        // What an earlier run held and its page never took went with that run.
        folder.deleteRecursively()
    }

    /** The files waiting, in the order they came. */
    val files: List<Entry> get() = synchronized(held) { held.toList() }

    /**
     * A file opened in the app, copied from [input] (which this closes) and
     * listed as [name] ("File" when it has none) of type [mime]; false when
     * it could not be read or written.
     */
    fun take(input: InputStream, name: String, mime: String): Boolean {
        val id = UUID.randomUUID().toString()
        val dir = File(folder, id)
        val copy = File(dir, safeFileName(name, "File"))
        val size = try {
            input.use { source ->
                if (!dir.mkdirs()) throw IOException("cannot make $dir")
                copy.outputStream().use { source.copyTo(it) }
            }
        } catch (e: IOException) {
            dir.deleteRecursively()
            return false
        } catch (e: SecurityException) {
            dir.deleteRecursively()
            return false
        }
        synchronized(held) { held.add(Entry(id, copy.name, size, mime, copy)) }
        onArrival?.invoke()
        return true
    }

    /** The `files` reply: `{files: [{id, name, size, type}]}`. */
    val listing: Map<String, Any?>
        get() = mapOf("files" to files.map { mapOf("id" to it.id, "name" to it.name, "size" to it.size, "type" to it.type) })

    /**
     * The `fileData` reply: up to [CHUNK_BYTES] of the file from [offset], as
     * base64 (`{data}`, "" at its end), or null when the shell holds no such
     * file or the offset is outside it.
     */
    fun chunk(id: String, offset: Long): Map<String, Any?>? {
        val entry = synchronized(held) { held.firstOrNull { it.id == id } } ?: return null
        if (offset < 0 || offset > entry.size) return null
        return try {
            RandomAccessFile(entry.file, "r").use { file ->
                file.seek(offset)
                val buffer = ByteArray(minOf(CHUNK_BYTES.toLong(), entry.size - offset).toInt())
                var filled = 0
                while (filled < buffer.size) {
                    val n = file.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                mapOf("data" to Base64.getEncoder().encodeToString(if (filled == buffer.size) buffer else buffer.copyOf(filled)))
            }
        } catch (e: IOException) {
            null
        }
    }

    /** The page is done with a file (imported, refused or put aside): its copy goes. An unknown id changes nothing. */
    fun done(id: String) {
        val entry = synchronized(held) {
            val index = held.indexOfFirst { it.id == id }
            if (index < 0) return
            held.removeAt(index)
        }
        entry.file.parentFile?.deleteRecursively()
    }

    companion object {
        /** The most one `fileData` reply carries, before base64. */
        const val CHUNK_BYTES = 2 * 1024 * 1024
    }
}

/** The longest name Android's file systems keep, in UTF-8 bytes. */
private const val MAX_NAME_BYTES = 255

/**
 * [name] as a file's name in a folder of the shell's: no "/" or NUL (each
 * becomes "-"), not "." or "..", at most 255 bytes with its extension kept,
 * and [fallback] when nothing is left.
 */
internal fun safeFileName(name: String, fallback: String): String {
    var safe = name.replace('/', '-').replace('\u0000', '-')
    if (safe.isBlank() || safe == "." || safe == "..") return fallback
    if (safe.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
        val dot = safe.lastIndexOf('.')
        val extension = if (dot > 0 && safe.length - dot <= 16) safe.substring(dot) else ""
        var stem = safe.substring(0, safe.length - extension.length)
        while (stem.isNotEmpty() && (stem + extension).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
        }
        safe = stem + extension
    }
    return safe
}
