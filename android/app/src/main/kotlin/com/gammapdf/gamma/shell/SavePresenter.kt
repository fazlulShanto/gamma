package com.gammapdf.gamma.shell

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.gammapdf.gamma.R
import com.gammapdf.gamma.app.MainActivity

/**
 * Where the files the page saves go: Downloads, in a Gamma folder
 * (Download/Gamma/), through MediaStore, which needs no permission; then a
 * banner at the bottom of the window says so and offers to open the file
 * (docs/dev/android.md "Saving files"). The iPad hands a download to its
 * share sheet (ipad/GammaIPad/Shell/Navigation.swift); a file saved with
 * no tap at all (an export that finished) would make a sheet or a picker
 * pop up unasked, so here it is just put away, and the banner says where.
 *
 * The bytes come three ways: a file the page sent over the bridge
 * ([saveFile], Saves), a URL the page asked the shell to fetch
 * ([saveUrl]), and a download the WebView itself started ([download]),
 * for anything the page did not route through the bridge. [saveUrl] and
 * [download] are called on the main thread and fetch on an IO one;
 * [saveFile] copies where it is called, off the main thread.
 */
class SavePresenter(
    private val activity: ComponentActivity,
    /** The view the banner is shown in: a FrameLayout over the whole window places it at the bottom. */
    private val banner: ViewGroup,
    private val scope: CoroutineScope,
) {
    /** A file in Downloads: what MediaStore called it and filed it as. */
    private class Saved(val uri: Uri, val name: String, val mime: String?)

    private val lookup = { ext: String -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }

    /** The banner showing now. */
    private var shown: View? = null

    /**
     * [file] into Downloads as [name], then the banner. It blocks until the
     * copy is made, so the caller can let the file go afterwards (Saves.release):
     * call it off the main thread.
     */
    @WorkerThread
    fun saveFile(file: File, name: String, mime: String) {
        val saved = try {
            file.inputStream().use { store(it, name, mime) }
        } catch (e: IOException) {
            failed(name, e)
            return
        } catch (e: RuntimeException) {
            failed(name, e)
            return
        }
        scope.launch(Dispatchers.Main.immediate) { show(saved) }
    }

    /**
     * What [url] serves into Downloads: the page's saveUrl, from a page of
     * [server]'s. [name] is the page's name for it, which wins over the
     * response's (SaveNames.choose).
     *
     * The shell fetches the http(s) addresses the page asks for, as a
     * browser downloads a link: a note's picture may come from another
     * site. The page may be any server's, though, and the shell's request
     * is held to none of the browser's rules, so only a request to [server]
     * carries the WebView's cookies (CookieManager.getCookie), and an
     * address on this device that is not [server] is not asked at all
     * (SaveHops); a redirect is followed hop by hop, each hop checked
     * (Fetch.get).
     */
    fun saveUrl(url: String, name: String, userAgent: String, server: Origin?) {
        fetch(url, userAgent, name, null, null, server)
    }

    /**
     * The WebView's DownloadListener: a download the page did not route
     * through the bridge, while [server] is shown. An http(s) one is
     * fetched as [saveUrl] fetches, named by the response's
     * Content-Disposition (then the listener's, then the URL); a blob: or
     * data: URL lives in the page, beyond the shell's reach, and the person
     * is told it could not be saved.
     */
    fun download(url: String, userAgent: String, contentDisposition: String?, mime: String?, server: Origin?) {
        fetch(url, userAgent, "", contentDisposition, mime, server)
    }

    private fun fetch(url: String, userAgent: String, name: String, disposition: String?, mime: String?, server: Origin?) {
        val scheme = UrlParts.schemeOf(url)
        if (scheme != "http" && scheme != "https") {
            Toast.makeText(activity, R.string.save_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val cookies = CookieManager.getInstance()
        scope.launch(Dispatchers.IO) {
            var shownName = SaveNames.choose(name, disposition, url)
            try {
                val connection = Fetch.get(url, userAgent, SaveHops(server)) { cookies.getCookie(it) }
                val saved = try {
                    val fileName = SaveNames.choose(name, connection.getHeaderField("Content-Disposition") ?: disposition, connection.url.toString())
                    shownName = fileName
                    connection.inputStream.use { store(it, fileName, connection.contentType ?: mime.orEmpty()) }
                } finally {
                    connection.disconnect()
                }
                launch(Dispatchers.Main) { show(saved) }
            } catch (e: IOException) {
                failed(shownName, e)
            } catch (e: RuntimeException) {
                failed(shownName, e)
            }
        }
    }

    /**
     * [input] into Downloads/Gamma as [name], hidden from other apps
     * (IS_PENDING) until it is all there. A name already taken gets
     * MediaStore's own " (1)"; what MediaStore made of the name and type is
     * what the banner shows and opens.
     */
    private fun store(input: InputStream, name: String, mime: String): Saved {
        val resolver = activity.contentResolver
        val fileName = safeFileName(name, SaveNames.FALLBACK)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            SaveNames.mediaMime(fileName, mime, lookup)?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Gamma")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Downloads took no new file")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Downloads gave no file to write")
            output.use { input.copyTo(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        var savedName = fileName
        var savedMime: String? = null
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE), null, null, null)?.use { row ->
            if (row.moveToFirst()) {
                row.getString(0)?.takeIf { it.isNotEmpty() }?.let { savedName = it }
                savedMime = row.getString(1)?.takeIf { it.isNotEmpty() }
            }
        }
        return Saved(uri, savedName, savedMime)
    }

    private fun failed(name: String, e: Exception) {
        val reason = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        scope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(activity, activity.getString(R.string.save_failed, name, reason), Toast.LENGTH_LONG).show()
        }
    }

    /** The banner, "Saved “name” to Downloads", with Open; a newer one takes its place. */
    private fun show(saved: Saved) {
        if (activity.isFinishing || activity.isDestroyed) return
        shown?.let(banner::removeView)
        val message = TextView(activity).apply {
            text = activity.getString(R.string.save_banner_saved, saved.name)
            setTextColor(BANNER_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxWidth = dp(420)
        }
        val open = TextView(activity).apply {
            text = activity.getString(R.string.save_banner_open)
            setTextColor(BANNER_ACTION)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            background = ripple()
            setOnClickListener { open(saved) }
        }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.save_banner)
            setPadding(dp(16), dp(4), dp(4), dp(4))
            elevation = dp(6).toFloat()
            addView(message)
            addView(open)
            alpha = 0f
        }
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val params = if (banner is FrameLayout) {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                                     Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        } else {
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        params.setMargins(dp(16), 0, dp(16), dp(16) + bottomInset())
        banner.addView(view, params)
        shown = view
        view.animate().alpha(1f).setDuration(FADE_MS)
        val accessibility = activity.getSystemService(AccessibilityManager::class.java)
        val timeout = accessibility?.getRecommendedTimeoutMillis(
            SHOWN_MS, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS) ?: SHOWN_MS
        view.postDelayed({ hide(view) }, timeout.toLong())
    }

    private fun hide(view: View) {
        if (shown !== view) return
        shown = null
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { banner.removeView(view) }
    }

    /** The saved file in an app of the person's choosing; Gamma itself is left out, as it would import it again. */
    private fun open(saved: Saved) {
        shown?.let(::hide)
        val view = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val type = saved.mime ?: FileTypes.extension(saved.name)?.let { FileTypes.ofExtension(it, lookup) }
        if (type != null) view.setDataAndType(saved.uri, type) else view.data = saved.uri
        val chooser = Intent.createChooser(view, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(
                ComponentName(activity, OpenedEntryActivity::class.java), ComponentName(activity, MainActivity::class.java)))
        try {
            activity.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.save_no_app, Toast.LENGTH_LONG).show()
        }
    }

    /** How much of the banner's view lies under the navigation bar, the keyboard or a cutout at the bottom. */
    private fun bottomInset(): Int {
        val insets = ViewCompat.getRootWindowInsets(banner)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            ?: return 0
        val place = IntArray(2)
        banner.getLocationInWindow(place)
        val below = banner.rootView.height - (place[1] + banner.height)
        return max(0, insets.bottom - below - banner.paddingBottom)
    }

    private fun ripple() = TypedValue().let { value ->
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        ContextCompat.getDrawable(activity, value.resourceId)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val SHOWN_MS = 4000
        const val FADE_MS = 150L

        // The banner is dark in both themes, as a snackbar is; the action in Gamma's accent, lightened to read on it.
        const val BANNER_TEXT = 0xFFF1F1F3.toInt()
        const val BANNER_ACTION = 0xFF8AB8E6.toInt()
    }
}
