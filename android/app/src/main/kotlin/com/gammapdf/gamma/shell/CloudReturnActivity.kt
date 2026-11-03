package com.gammapdf.gamma.shell

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.gammapdf.gamma.app.MainActivity

/**
 * Where the browser ends a Gamma Cloud sign-in run in a Custom Tab:
 * gamma-android://cloud-signin, which the loopback sends it to once it has
 * the answer (CloudWire.loopbackResponse). It hands the return to the
 * running CloudSignIn and brings the app's window forward, which closes
 * the tab above it (docs/dev/android.md "Gamma Cloud sign-in"). It shows
 * nothing itself. An Auth Tab hands the redirect back by itself and never
 * comes here.
 */
class CloudReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { CloudSignIn.returned(it) }
        // NEW_TASK: a browser may deliver the return in its own task; the window is in Gamma's.
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
