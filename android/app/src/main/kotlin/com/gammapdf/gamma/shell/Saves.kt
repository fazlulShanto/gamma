package com.gammapdf.gamma.shell

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * The files the page hands the shell to save (`save`, `saveData`,
 * `saveDone`; frontend/src/shared/lib/saveFile.js), put together in a
 * temporary file each until it is complete, then presented
 * (shell/SavePresenter.kt) — a WebView's download listener can neither read
 * a blob: URL nor learn an <a download> name, so the bytes come over the
 * bridge (docs/dev/android.md "Saving files"). A file is begun with the
 * exact size it will have, its bytes come in order, and it is complete only
 * at that size.
 *
 * Safe to use from any thread; [append] writes to the file, so it runs off
 * the main thread.
 */
class Saves(
    /** Where the files being saved are, one folder each so the name is kept (cacheDir/Saves). */
    val folder: File,
) {
    private class Pending(val mime: String, val size: Long, val file: File) {
        var written = 0L
    }

    private val pending = HashMap<String, Pending>()

    init {
        // A save an earlier run did not finish, or a file it presented, went with that run.
        folder.deleteRecursively()
    }

    /**
     * A file of [size] bytes to save as [name] ("Download" when it has
     * none): its id, or null when it cannot be (a negative size, or more
     * than the folder's disk has free).
     */
    fun begin(name: String, mime: String, size: Long): String? {
        if (size < 0) return null
        val id = UUID.randomUUID().toString()
        val dir = File(folder, id)
        val file = File(dir, safeFileName(name, "Download"))
        try {
            if (!dir.mkdirs() || size > dir.usableSpace || !file.createNewFile()) throw IOException("cannot save in $dir")
        } catch (e: IOException) {
            dir.deleteRecursively()
            return null
        } catch (e: SecurityException) {
            dir.deleteRecursively()
            return null
        }
        synchronized(pending) { pending[id] = Pending(bareMime(mime), size, file) }
        return id
    }

    /**
     * The next [bytes] of the file [id], which must start at [offset], the
     * bytes so far, and not run past its size; false otherwise, or when they
     * could not be written.
     */
    fun append(id: String, offset: Long, bytes: ByteArray): Boolean {
        val save = synchronized(pending) { pending[id] } ?: return false
        synchronized(save) {
            if (offset != save.written || save.written + bytes.size > save.size) return false
            try {
                FileOutputStream(save.file, true).use { it.write(bytes) }
            } catch (e: IOException) {
                return false
            }
            save.written += bytes.size
        }
        return true
    }

    /** The MIME type the page gave the file [id], without its parameters ("" when none); null for no such file. */
    fun mime(id: String): String? = synchronized(pending) { pending[id] }?.mime

    /**
     * The file [id] is complete: the file, to present, when it has the size
     * it was begun with. Cancelled, short or unknown: null, and what there
     * was of it goes.
     */
    fun finish(id: String, cancel: Boolean): File? {
        val save = synchronized(pending) { pending.remove(id) } ?: return null
        synchronized(save) {
            if (cancel || save.written != save.size) {
                save.file.parentFile?.deleteRecursively()
                return null
            }
            return save.file
        }
    }

    /** A finished file has been presented (copied out, or shared and done with): its folder goes. */
    fun release(file: File) {
        val dir = file.parentFile ?: return
        if (dir.parentFile == folder) dir.deleteRecursively()
    }

    /**
     * Every save not finished is dropped: the document that was sending them
     * is gone (HostBridge.onDocumentGone), and no other knows their ids. They
     * are dropped at once, so a save begun afterwards is not among them; what
     * was written of them goes when the returned step runs, which waits for a
     * chunk being written and may delete hundreds of MB: off the main thread.
     */
    fun cancelAll(): () -> Unit {
        val dropped = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        return { for (save in dropped) synchronized(save) { save.file.parentFile?.deleteRecursively() } }
    }

    private fun bareMime(mime: String): String = mime.substringBefore(';').trim().lowercase()
}
