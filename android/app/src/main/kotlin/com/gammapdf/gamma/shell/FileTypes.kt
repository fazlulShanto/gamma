package com.gammapdf.gamma.shell

import java.util.Locale

/**
 * MIME types for the files the shell takes in and offers: an opened file's
 * `type` (shell/OpenedIntake.kt), the types the file chooser offers
 * (shell/FileChooser.kt) and a saved file's (shell/SavePresenter.kt).
 * [lookup] is the system's table by extension, MimeTypeMap's on a device;
 * Markdown is added by hand, since that table lacks it on some versions.
 */
object FileTypes {
    /** What a provider calls a folder (DocumentsContract.Document.MIME_TYPE_DIR). */
    const val FOLDER = "vnd.android.document/directory"

    /** Any file: what a picker offers when the page's types cannot all be named. */
    val ANY: List<String> = listOf("*/*")

    private val BY_HAND = mapOf("md" to "text/markdown", "markdown" to "text/markdown")
    private val TYPE = Regex("^[a-z0-9!#$&^_.+-]+/(?:[a-z0-9!#$&^_.+-]+|\\*)$")

    /** The lowercased extension of the file name [name] ("pdf" for "Paper.PDF"); null when it has none. */
    fun extension(name: String): String? {
        val base = name.substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) return null
        return base.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** The MIME type of [extension] (with or without its dot); null when none is known. */
    fun ofExtension(extension: String, lookup: (String) -> String?): String? {
        val ext = extension.removePrefix(".").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return null
        return BY_HAND[ext] ?: lookup(ext)?.lowercase(Locale.ROOT)?.takeIf { TYPE.matches(it) }
    }

    /**
     * An opened file's type: its name's extension's, as the iPad gives it;
     * else what its provider says ([providerType], ContentResolver.getType),
     * which also covers a name without an extension; else "".
     */
    fun opened(name: String, providerType: String?, lookup: (String) -> String?): String =
        extension(name)?.let { ofExtension(it, lookup) }
            ?: providerType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { TYPE.matches(it) && it != FOLDER }
            ?: ""

    /**
     * An <input accept>'s tokens (".pdf", "application/pdf", any image, as
     * WebChromeClient.FileChooserParams.getAcceptTypes gives them) as the
     * MIME types a document picker offers, in order without repeats. A
     * token with no known type (".edn", ".goodnotes"), or none at all,
     * opens the picker to every file: a narrower picker would hide a file
     * the page takes, and the server checks what it is given anyway.
     */
    fun accepted(tokens: List<String>, lookup: (String) -> String?): List<String> {
        val types = LinkedHashSet<String>()
        for (raw in tokens.flatMap { it.split(',') }) {
            val token = raw.trim().lowercase(Locale.ROOT)
            if (token.isEmpty()) continue
            val type = if ('/' in token) token.substringBefore(';').trim().takeIf { TYPE.matches(it) } else ofExtension(token, lookup)
            if (type == null || type == "*/*") return ANY
            types += type
        }
        return if (types.isEmpty()) ANY else types.toList()
    }
}

/** What the file chooser opens for an <input type=file> (shell/FileChooser.kt). */
sealed interface Pick {
    /** The Photo Picker, for a page that takes pictures only (an accept of any image). */
    data class Photos(val multiple: Boolean) : Pick

    /** The system's document picker, offering [types]. */
    data class Documents(val types: List<String>, val multiple: Boolean) : Pick

    companion object {
        // WebChromeClient.FileChooserParams' modes; the folder mode is public from API 37 only.
        const val MODE_OPEN = 0
        const val MODE_OPEN_MULTIPLE = 1
        const val MODE_OPEN_FOLDER = 2
        const val MODE_SAVE = 3

        /**
         * The picker for [acceptTypes] in [mode]; null for a save, which no
         * page of Gamma's asks for (its downloads come over the bridge). A
         * folder (`webkitdirectory`) is picked as several files: Android's
         * pickers hand back no folder a page could read.
         */
        fun of(acceptTypes: List<String>, mode: Int, lookup: (String) -> String?): Pick? {
            val multiple = when (mode) {
                MODE_OPEN -> false
                MODE_OPEN_MULTIPLE, MODE_OPEN_FOLDER -> true
                else -> return null
            }
            val types = FileTypes.accepted(acceptTypes, lookup)
            return if (types == listOf("image/*")) Photos(multiple) else Documents(types, multiple)
        }
    }
}
