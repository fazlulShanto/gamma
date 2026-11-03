package com.gammapdf.gamma.app

import android.app.ActivityManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import com.gammapdf.gamma.R
import com.gammapdf.gamma.shell.CloudSignIn
import com.gammapdf.gamma.shell.FileChooser
import com.gammapdf.gamma.shell.Microphone
import com.gammapdf.gamma.shell.OpenedIntake
import com.gammapdf.gamma.shell.ShellController
import com.gammapdf.gamma.shell.Shortcut

/**
 * The app's one window: the shell (ShellController) edge to edge, made once
 * per activity. The manifest keeps the activity through rotation, a
 * keyboard and the system going light or dark (configChanges), so the
 * WebViews and what they show stay. It is singleTop, so a tap on the app's
 * icon only brings its task forward and leaves a sign-in tab or a picker
 * open above it. Files opened in the app (VIEW, SEND, SEND_MULTIPLE) come
 * through OpenedEntryActivity, to this window when it is open (onNewIntent),
 * and go to OpenedIntake; the system's back and the shortcuts go to the
 * shell (docs/dev/android.md "The shell").
 */
class MainActivity : ComponentActivity() {
    private lateinit var shell: ShellController

    private val app: GammaApplication get() = application as GammaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isSecondWindow()) {
            finish()
            return
        }
        // The shell paints the strips under the system bars itself, in the page's colour.
        WindowCompat.enableEdgeToEdge(window)
        window.isNavigationBarContrastEnforced = false
        // The services that register for activity results are made now, before the activity starts.
        shell = ShellController(this, app, CloudSignIn(this, app.scope), FileChooser(this), Microphone(this))
        setContentView(shell.root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!shell.back()) moveTaskToBack(true)
            }
        })
        // An activity made again (the system ended it meanwhile) took its intent's files the first time.
        if (savedInstanceState == null) take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    /**
     * Whether this is a second window that a tap on the app's icon opened
     * above the first in the app's task. The system does that when something
     * is open above the first window (a sign-in tab, a picker) and the task
     * was last brought up by another intent (a file opened in the app, the
     * Gamma Cloud sign-in's return): it closes at once, so the tap only
     * brings the task forward, as it does otherwise.
     */
    private fun isSecondWindow(): Boolean {
        val launcher = intent?.run { action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_LAUNCHER) } == true
        if (isTaskRoot || !launcher) return false
        val tasks = getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
        return tasks.any { task ->
            val info = try {
                task.taskInfo
            } catch (e: IllegalArgumentException) {
                null // a task gone meanwhile
            }
            info != null && info.taskId == taskId && info.baseActivity == componentName
        }
    }

    /** The files an intent opens in the app; one reopened from Recents was taken when it came. */
    private fun take(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        OpenedIntake.take(this, intent, app.opened, app.scope)
    }

    override fun onStart() {
        super.onStart()
        shell.resume()
    }

    override fun onStop() {
        shell.pause()
        super.onStop()
    }

    override fun onDestroy() {
        if (::shell.isInitialized) shell.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        shell.configurationChanged()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) shell.penLayer.onWindowFocusLost()
    }

    /** Pen buttons first (PenLayer), then the shell's shortcuts, before any WebView has the key. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shell.penLayer.dispatchStylusKey(event)) return true
        val shortcut = Shortcut.of(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed)
        if (shortcut != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) shell.shortcut(shortcut)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** The shortcuts in the system's keyboard shortcuts sheet (Meta+/). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val items = Shortcut.entries.map { shortcut ->
            val title = when (shortcut) {
                Shortcut.ALL_SERVERS -> R.string.shortcut_all_servers
                Shortcut.RELOAD -> R.string.shortcut_reload
                Shortcut.TOGGLE_BAR -> if (shell.barIsHidden) R.string.shortcut_show_bar else R.string.shortcut_hide_bar
            }
            val modifiers = KeyEvent.META_CTRL_ON or (if (shortcut.shift) KeyEvent.META_SHIFT_ON else 0)
            KeyboardShortcutInfo(getString(title), shortcut.keyCode, modifiers)
        }
        data.add(KeyboardShortcutGroup(getString(R.string.app_name), items))
    }
}
