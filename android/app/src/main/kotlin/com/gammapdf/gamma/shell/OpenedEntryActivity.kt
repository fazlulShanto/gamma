package com.gammapdf.gamma.shell

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.gammapdf.gamma.app.MainActivity

/**
 * Where the files another app opens in Gamma arrive: Files' Open with and a
 * share sheet's Gamma (the manifest's VIEW, SEND and SEND_MULTIPLE
 * filters). It hands a copy of the intent to the app's window in the app's
 * own task and closes; the window gives the files to OpenedIntake. It shows
 * nothing itself. It is apart from the window so that the window can be
 * singleTop: a tap on the app's icon then only brings the task forward,
 * while a file opened in the app still comes to the window already open
 * (onNewIntent) and closes what is above it, a sign-in tab or a picker
 * (docs/dev/android.md "Opened files").
 */
class OpenedEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val received = intent
        val forward = if (received == null || received.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            // Reopened from Recents: its files were taken when they came, and their grant is gone.
            Intent()
        } else {
            // The data, the clip and the extras, with the grant to read them,
            // which passes to the window and outlasts this activity. The
            // sender's own launch flags (a new document, no history) are not
            // the window's.
            Intent(received).setFlags(received.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        forward.setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(forward)
        } catch (e: SecurityException) {
            // A grant the system will not pass on: the window comes all the
            // same, and OpenedIntake names the files it cannot read.
            startActivity(forward.setFlags(forward.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()))
        }
        finish()
    }
}
