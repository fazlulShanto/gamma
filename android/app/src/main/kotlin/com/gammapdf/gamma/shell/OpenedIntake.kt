package com.gammapdf.gamma.shell

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.IntentCompat
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gammapdf.gamma.R

/**
 * The files opened in the app, from the intents that bring them: Files'
 * Open with (ACTION_VIEW) and a share sheet's Gamma (ACTION_SEND,
 * ACTION_SEND_MULTIPLE). Each is copied into OpenedFiles, which holds it
 * until the page takes it, as the iPad's onOpenURL hands files to its store
 * (ipad/GammaIPad/App/GammaApp.swift; docs/dev/android.md "Opened files").
 * The original is only read, never moved or deleted.
 */
object OpenedIntake {
    /** What the person is told a file without a name is called, as OpenedFiles lists it. */
    private const val UNNAMED = "File"

    /**
     * Takes the files [intent] carries into [store], copying them on an IO
     * thread of [scope]; true when it carried any. A folder, a file that
     * cannot be read, and one of the app's own files are refused, and the
     * person is told.
     */
    fun take(context: Context, intent: Intent, store: OpenedFiles, scope: CoroutineScope): Boolean {
        val uris = uris(intent).filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }.distinct()
        if (uris.isEmpty()) return false
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val refused = uris.mapNotNull { uri -> copy(app, uri, store) }
            if (refused.isEmpty()) return@launch
            val message = if (refused.size == 1) {
                app.getString(R.string.open_refused, refused[0])
            } else {
                app.resources.getQuantityString(R.plurals.open_refused_several, refused.size, refused.size)
            }
            withContext(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        }
        return true
    }

    private fun uris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            .ifEmpty { clipUris(intent).take(1) }
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.filterNotNull().orEmpty().ifEmpty { clipUris(intent) }
        else -> emptyList()
    }

    private fun clipUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    /** Copies [uri] into [store]: null when it is there, else the name to tell the person. */
    private fun copy(context: Context, uri: Uri, store: OpenedFiles): String? {
        val lookup = { ext: String -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }
        val resolver = context.contentResolver
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(uri.path ?: return UNNAMED)
            val name = file.name
            val shown = name.ifEmpty { UNNAMED }
            if (!file.isFile || isPrivate(context, file)) return shown
            val input = try {
                FileInputStream(file)
            } catch (e: IOException) {
                return shown
            } catch (e: SecurityException) {
                return shown
            }
            return if (store.take(input, name, FileTypes.opened(name, null, lookup))) null else shown
        }
        val name = displayName(resolver, uri) ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val shown = name.ifEmpty { UNNAMED }
        if (isOwn(context, uri)) return shown
        val providerType = try {
            resolver.getType(uri)
        } catch (e: RuntimeException) {
            null
        }
        if (providerType == FileTypes.FOLDER) return shown
        val input: InputStream = try {
            resolver.openInputStream(uri) ?: return shown
        } catch (e: IOException) {
            return shown
        } catch (e: RuntimeException) {
            return shown // SecurityException without a grant, IllegalArgumentException from a provider
        }
        return if (store.take(input, name, FileTypes.opened(name, providerType, lookup))) null else shown
    }

    /** OpenableColumns.DISPLAY_NAME, the name the person sees for it; null when the provider gives none. */
    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: RuntimeException) {
        null
    }

    /**
     * Whether [uri] is served by a provider of this app's own (its
     * FileProvider): another app could name one of the app's files that
     * way, and the app can always read its own.
     */
    private fun isOwn(context: Context, uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        val provider = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveContentProvider(authority, 0)
        }
        return provider?.packageName == context.packageName
    }

    /** Whether [file] is inside the app's own data, which a file:// URI from another app must not reach. */
    private fun isPrivate(context: Context, file: File): Boolean {
        val path = try {
            file.canonicalPath
        } catch (e: IOException) {
            return true
        }
        val roots = listOf(context.dataDir, context.createDeviceProtectedStorageContext().dataDir)
        return roots.any { root ->
            val base = try {
                root.canonicalPath
            } catch (e: IOException) {
                root.absolutePath
            }
            path == base || path.startsWith(base + File.separator)
        }
    }
}
