package com.gammapdf.gamma.shell

/**
 * The shell's keyboard shortcuts, the iPad's key commands
 * (ipad/GammaIPad/Shell/ShellView.swift keyCommands) with Ctrl for ⌘, as
 * desktop/ui/bar.html names them on Android. MainActivity takes them before
 * the WebViews do and lists them in the system's shortcut sheet.
 */
enum class Shortcut(
    /** android.view.KeyEvent's code (ShellKeysTest holds each to android.jar's). */
    val keyCode: Int,
    val shift: Boolean,
) {
    /** Ctrl+Shift+L: the launcher, open or closed. */
    ALL_SERVERS(40, true),

    /** Ctrl+R: the page again. */
    RELOAD(46, false),

    /** Ctrl+Shift+B: the bar hidden or shown. */
    TOGGLE_BAR(30, true);

    companion object {
        /** The shortcut a key with these modifiers is, exactly (Ctrl+Shift+R is no reload), or null. */
        fun of(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): Shortcut? =
            if (!ctrl || alt || meta) null else entries.firstOrNull { it.keyCode == keyCode && it.shift == shift }
    }
}

/**
 * What the system's back does, which the iPad has no counterpart of
 * (docs/dev/android.md "The shell"): the first that applies.
 */
enum class BackStep {
    /** The bar's dropdown closes. */
    CLOSE_MENU,

    /** The launcher closes over the server shown. */
    CLOSE_LAUNCHER,

    /** The page goes back in its history. */
    PAGE_BACK,

    /** The app goes to the background, kept as it is. */
    LEAVE;

    companion object {
        /**
         * [launcherCloses]: the launcher is up over a chosen server and shows
         * no failure. A launcher that does (it is the failed page's message)
         * or that has no server under it is left as it is: the page under it
         * is not walked back unseen.
         */
        fun of(menuOpen: Boolean, launcherUp: Boolean, launcherCloses: Boolean, canGoBack: Boolean): BackStep = when {
            menuOpen -> CLOSE_MENU
            launcherUp -> if (launcherCloses) CLOSE_LAUNCHER else LEAVE
            canGoBack -> PAGE_BACK
            else -> LEAVE
        }

        /**
         * Whether the page can go back to [previous], the history's entry
         * before the current one: only on the origin of [server], the server
         * shown. One WebView shows every server, and the WebView's own back
         * passes neither Route nor the bridge, which is set up for the
         * server shown alone, so an entry another server left (PageView
         * clears them once the new server's page is in) is not gone back to.
         */
        fun staysOnServer(previous: String?, server: String?): Boolean {
            val origin = Origin.of(server) ?: return false
            return Origin.of(previous) == origin
        }
    }
}
