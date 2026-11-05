package com.gammapdf.gamma.shell

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * The microphone for the page's recordings and dictation (getUserMedia):
 * granted to the server's own page once the person has let the app record
 * (RECORD_AUDIO), as the iPad grants it to the server's origin
 * (ipad/GammaIPad/Shell/Navigation.swift requestMediaCapturePermission).
 * Any other origin, and anything but the microphone, is denied, and so is
 * the page when the person says no (docs/dev/android.md "Recording").
 *
 * Main-thread confined. Made in the activity's onCreate: it registers the
 * permission request there.
 */
class Microphone(private val activity: ComponentActivity) {
    /** The page's requests waiting for the person's answer to the system's question. */
    private val waiting = ArrayList<PermissionRequest>()

    private val ask = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val answered = waiting.toList()
        waiting.clear()
        for (request in answered) if (granted) grant(request) else request.deny()
    }

    /** WebChromeClient.onPermissionRequest, with the server the page should be on. */
    fun request(request: PermissionRequest, server: Origin?) {
        val origin = Origin.of(request.origin?.toString())
        if (server == null || origin != server || PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) {
            request.deny()
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            grant(request)
            return
        }
        waiting += request
        if (waiting.size == 1) ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** WebChromeClient.onPermissionRequestCanceled: the page no longer wants it. */
    fun cancel(request: PermissionRequest) {
        waiting.remove(request)
    }

    private fun grant(request: PermissionRequest) {
        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
    }
}
