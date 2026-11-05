package com.gammapdf.gamma.shell

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * What a file the shell saves is called and filed as (shell/SavePresenter.kt;
 * docs/dev/android.md "Saving files").
 */
object SaveNames {
    /** The name of a file nothing names. */
    const val FALLBACK = "Download"

    /**
     * A saved URL's name: the page's [pageName] when it gave one, since it
     * is the person's name for the file (an upload is served under its
     * hash, a job's artifact under the job's); then the response's
     * Content-Disposition ([disposition]); then the last segment of the
     * [url]'s path; then "Download".
     */
    fun choose(pageName: String, disposition: String?, url: String): String =
        pageName.takeIf { it.isNotBlank() } ?: fileName(disposition) ?: lastSegment(url) ?: FALLBACK

    /**
     * The file name a Content-Disposition header gives: its `filename*`
     * (RFC 8187: UTF-8 or ISO-8859-1, percent-encoded) when that reads,
     * else its `filename` (a token or a quoted string); null when it gives
     * none. Of a name with a path, only the last part is kept, as a browser
     * keeps it.
     */
    fun fileName(disposition: String?): String? {
        val parameters = parameters(disposition ?: return null)
        val name = parameters["filename*"]?.let(::extendedValue) ?: parameters["filename"]
        return name?.let(::baseName)
    }

    /** The last segment of [url]'s path, percent-decoded; null when there is none. */
    fun lastSegment(url: String): String? = UrlParts.parse(url)?.path?.let(::baseName)

    /**
     * The MIME type to file [name] under in MediaStore, which adds the
     * extension of a type its name does not end in: the type of the name's
     * extension ([lookup] is MimeTypeMap's), or none for an extension no
     * table knows, so the name stays as it is; for a name with no
     * extension, the type it came with ([given]), whose extension
     * MediaStore then adds, unless that says no more than "bytes".
     */
    fun mediaMime(name: String, given: String?, lookup: (String) -> String?): String? {
        FileTypes.extension(name)?.let { return FileTypes.ofExtension(it, lookup) }
        val type = given?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return type?.takeIf { '/' in it && it != "application/octet-stream" }
    }

    /** A header's parameters by lowercased name, the first of each; a quoted value unquoted. */
    private fun parameters(header: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in split(header).drop(1)) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim().lowercase(Locale.ROOT)
            val value = part.substring(eq + 1).trim()
            if (name !in out) out[name] = unquote(value)
        }
        return out
    }

    /** [header] split at each ';' outside a quoted string. */
    private fun split(header: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        for (c in header) {
            when {
                escaped -> escaped = false
                quoted && c == '\\' -> escaped = true
                c == '"' -> quoted = !quoted
                c == ';' && !quoted -> {
                    parts += current.toString()
                    current.clear()
                    continue
                }
            }
            current.append(c)
        }
        parts += current.toString()
        return parts
    }

    private fun unquote(value: String): String {
        if (value.length < 2 || !value.startsWith('"') || !value.endsWith('"')) return value
        val out = StringBuilder()
        var escaped = false
        for (c in value.substring(1, value.length - 1)) {
            if (!escaped && c == '\\') {
                escaped = true
                continue
            }
            escaped = false
            out.append(c)
        }
        return out.toString()
    }

    /** An RFC 8187 value (charset'language'percent-encoded) decoded; null when it does not read. */
    private fun extendedValue(value: String): String? {
        val parts = value.split('\'', limit = 3)
        if (parts.size != 3) return null
        val charset = when (parts[0].trim().lowercase(Locale.ROOT)) {
            "utf-8" -> Charsets.UTF_8
            "iso-8859-1" -> Charsets.ISO_8859_1
            else -> return null
        }
        val bytes = ByteArrayOutputStream()
        val text = parts[2]
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val high = Character.digit(text[i + 1], 16)
                val low = Character.digit(text[i + 2], 16)
                if (high < 0 || low < 0) return null
                bytes.write(high * 16 + low)
                i += 3
            } else {
                if (c.code > 0x7e || c.code < 0x21) return null
                bytes.write(c.code)
                i += 1
            }
        }
        return decode(bytes.toByteArray(), charset)
    }

    private fun decode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun baseName(name: String): String? =
        name.substringAfterLast('/').substringAfterLast('\\').trim().takeIf { it.isNotEmpty() && it != "." && it != ".." }
}
