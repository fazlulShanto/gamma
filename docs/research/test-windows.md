# Test runs and the developer's screen

The question: the developer works at the machine, over Remote Desktop, while
agents run tests, and a run that opens a window takes the screen and the
keyboard. Which local runs open one, and what keeps each out of the way?
Surveyed and measured on 5 November 2026, on Windows 11 with Electron 38.8.6.
The mechanics now in force are in
[desktop/docs/architecture.md](../../desktop/docs/architecture.md) and the rule
in [dev/debugging.md](../dev/debugging.md#local-changes-test-the-affected-modules).

## What opens a window

| Run | By default | Switch |
|---|---|---|
| `desktop/test/e2e.js` | minimized | `--headed` |
| `desktop/test/security.e2e.js` | minimized | `--headed` |
| `desktop/test/smoke.js` | minimized locally, a normal window in CI | `--headed` |
| `npm start`, `desktop/start.cmd` | a normal window on the real profile | the developer's own start |
| `frontend/tests/e2e` | headless | `--headed`: a window per worker |
| The extension's e2e, `latexEditor.mjs`, `markdownSecurity.mjs` | headless | `--headed` |
| `tools/readme-media`, `tools/branding`, `tools/user_guide` | headless | none: what they make is pixels at a fixed screen |
| An Android emulator | a window | `-no-window`, as CI starts it; `shoot-play.mjs` was run that way |
| The iPad's XCTest | Mac only, no window under `xcodebuild` | none |

`--headed` and `GAMMA_TEST_HEADED=1` are one switch, `tools/headed.cjs`, which
every suite reads and `desktop/test/headed.test.js` keeps that way.
`tools/readme-media/runtime.mjs` passes `headless: false` with `--headless=new`
to keep the extension recorder headless, so that pair stays together.

## Keeping the desktop window down

The shell's test mode (`TEST_MINIMIZED`) opened the window minimized, and four
e2e steps still brought it up with `restore()`, `show()` and `focus()`: a PDF
handed to the running app, a PDF at launch, a sign-in come back and the
background step. A focus log (the foreground window's process, polled every
50 ms, one line per change) then showed the launch itself taking the
foreground:

| Launch | `electron` held the foreground, minimized | With `setFocusable(false)` first |
|---|---|---|
| `security.e2e.js` | 1.2 s | never, in 11 runs |
| `smoke.js` | 3.0 s | never, in 3 runs |
| `e2e.js`, first start | 8.7 s, until the developer clicked elsewhere | never, in 2 full runs |
| `e2e.js`, relaunch | 3.2 s | never |
| `e2e.js`, a PDF at launch | 3.7 s | never |

One blip of about 90 ms remains, some 41 s into a full run, just after the
background step: it showed before the change and in two of the three full runs
after it. It was not traced further.

`minimize()` on a window never shown goes to `Show(kMinimized)`
(`native_window_views.cc`), which is `SW_SHOWMINIMIZED` and activates by
Microsoft's definition; the window is minimized, but it holds the keyboard.
`setFocusable(false)` before it turns activation off (`WS_EX_NOACTIVATE`, no
`CanActivate`), and on Windows it calls `SetSkipTaskbar(true)` as well, so the
taskbar button goes too. `showInactive()` is `SW_SHOWNOACTIVATE`, which also
restores a minimized window, so the background step can test the way back
without the keyboard: 35 of 35 steps pass with it.

The focus log can only see the foreground. A window restored without focus
does not show in it, which is what the suite's own check is for: on Windows a
step that leaves the window up fails.

## The alternative not taken: a parked window

The window really shown, left of every display, unfocusable and without a
taskbar button. Nothing would appear even in the background step, and the
device emulation a minimized window needs for its page size would go. The
design survived a review of the Electron and Chromium sources with no step it
would break, but it was declined for now: the brief was a minimized window, a
display change over Remote Desktop might bring it back into view, and its
remaining cases can only be settled by running them. If the background step's
moment on the screen ever matters, this is the next step.

Two things that review corrected, for whoever picks it up. `document.visibilityState`
proves nothing under Playwright: its focus emulation counts as a capturer, so
every driven page reports `visible` whatever the window does; only `--smoke`,
with no driver, shows a page's real state. And a page that was never shown is
not what stops a hidden window: the blocker is that minimizing an unshown window
activates it.

## Two flakes the runs turned up

- **A hook's registry write could vanish** (`desktop/test/e2e.js`, the step
  *remote server: a URL*, about one run in six). Playwright's `evaluate` goes
  through Node's inspector, which runs a message at a V8 interrupt, in the
  middle of whatever the main process is executing. A write there landed inside
  `reconcileMirrors`' save, between its load and its write, and that save took
  it back. No app code runs that way (an IPC handler gets a turn of the event
  loop), so this was the harness's alone; `hook` now gives its body such a turn.
- **`security.e2e.js` could refuse its own port.** It asserted that `listen(0)`
  gave a port from 9100 up, and this machine's dynamic range starts at 1024
  (`netsh interface ipv4 show dynamicport tcp`). It now asks again below 9100.

## Still open

- The clone step logs `Could not open the origin.` or `Could not clone the
  workspace.` with a `loadURL` rejected as `ERR_FAILED (-2)` in most runs, and
  in some the rejection fails the step. Those were native boxes in the middle
  of a run until now. The likely cause, not yet proven: `openServer` can end in
  the auto-login's `location.reload()`, and `openGammaWorkspace`'s `loadURL`
  then starts under that reload. Whether a normal window hits it too was not
  tested, since that takes a window on the screen; if it does, a user can see
  that box.
- `extension/tests/prerender.e2e.mjs` fails with `chrome is not defined`, on
  `dev` as it stood before the one switch too.
- `shell.openPath` behind the launcher's *Open data folder* and
  `shell.showItemInFolder` behind *Show server log* and *Show log file* have no
  test guard, unlike the helpers beside them. No step reaches them.
- The menu's *Check for updates* box is not guarded either, and no step reaches
  it.
