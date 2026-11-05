package com.gammapdf.gamma.shell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The page's <input type=file>: a WKWebView picks files itself, a WebView
 * only through its app (docs/dev/android.md "Picking files"). A page that
 * takes pictures only (any image, a note's Add picture) gets the
 * Photo Picker; anything else the system's document picker, offering the
 * types the input accepts (Pick, FileTypes.accepted). The WebView's
 * callback is always answered, with null when nothing was picked: one left
 * unanswered keeps the page from ever opening a picker again.
 *
 * Main-thread confined. Made in the activity's onCreate: it registers the
 * pickers there.
 */
class FileChooser(private val activity: ComponentActivity) {
    private var pending: ValueCallback<Array<Uri>>? = null

    private val documents = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val picked = ArrayList<Uri>()
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(picked::add) }
            data.data?.let(picked::add)
        }
        answer(picked)
    }

    private val photo = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        answer(listOfNotNull(uri))
    }

    private val photos = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        answer(uris)
    }

    /** WebChromeClient.onShowFileChooser: true, always, as the callback is always answered. */
    fun show(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        // A picker the page asked for before and never heard back from is over.
        pending?.onReceiveValue(null)
        pending = null
        val pick = Pick.of(params.acceptTypes?.toList().orEmpty(), params.mode) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        if (pick == null) {
            callback.onReceiveValue(null)
            return true
        }
        pending = callback
        try {
            when (pick) {
                is Pick.Photos -> {
                    val request = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()
                    if (pick.multiple) photos.launch(request) else photo.launch(request)
                }
                is Pick.Documents -> documents.launch(documentIntent(pick))
            }
        } catch (e: ActivityNotFoundException) {
            answer(emptyList())
        }
        return true
    }

    private fun documentIntent(pick: Pick.Documents): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            if (pick.types.size == 1) {
                type = pick.types[0]
            } else {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, pick.types.toTypedArray())
            }
            if (pick.multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

    /**
     * The page gets what was picked, null for nothing. Only content URIs
     * go: a file path from a picker could name the app's own files.
     */
    private fun answer(picked: List<Uri>) {
        val callback = pending ?: return
        pending = null
        val uris = picked.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.distinct()
        callback.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
}
