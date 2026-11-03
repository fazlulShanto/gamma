"""Entry module of the Gamma server inside the mobile apps, the iPad's and
Android's.

Each app runs one CPython 3.13 in its own process (the iPad's embedded
Python.xcframework, ipad/GammaIPad/Server/PythonRuntime.swift; Android's
Chaquopy, android/app/src/main/kotlin/com/gammapdf/gamma/server/PythonRuntime.kt)
and calls ``gamma_mobile.run(env)`` on a thread of its own, where it blocks
for the app's life. ``env`` is the server's environment, a dict or a JSON
object: GAMMA_DATA_DIR, GAMMA_STATIC_DIR, GAMMA_PORT, GAMMA_VERSION,
GAMMA_CLOUD_DEFAULT_ISSUER, TMPDIR on Android, and GAMMA_ADMIN_* on a first
start. The iPad sets those in the process before the interpreter starts and
passes none; Android passes them, since its interpreter may have started
before. This is desktop/backend_entry.py for the apps: there the
configuration comes from argv and the shell keeps the output; here it comes
from the app and the output goes to a file.

The interpreter starts once per process and is never finalized, so the
server is run under a supervisor (``supervise``): when it stops, or could
not listen, the supervisor writes why to ``<data dir>/logs/server.state``
and waits; ``restart()``, which the app calls on another thread
(PythonRuntime.restart in both apps), stops a server still running and runs
it again on the same port. That is how a failed start is retried and a
server that stopped answering is replaced (iOS reclaims a suspended app's
listening sockets), without opening the app again.

The iPad's build copies this file into the app bundle's python/app/, next to
app.py and the gamma package (ipad/scripts/build-python.sh); Android's build
packages the three the same way (android/app/build.gradle.kts).
"""

import faulthandler
import json
import os
import sys
import threading
import time
import traceback
from datetime import datetime, timezone

# The supervisor's state for the app, in logs/ beside the log: {"state":
# "starting" | "stopped", "detail", "at"}. The app reads it while it waits
# for /api/health to answer: "stopped" is a start that failed (detail says
# how), shown with a Try again. Written whole and renamed into place, so a
# read never sees half of it.
STATE_FILE = "server.state"

# The stack of every thread the server starts on Android: glibc's default,
# which the server has on Linux. Android's threads get bionic's 1 MiB, while
# CPython gives its own 16 MiB on Apple platforms, and the request handlers'
# worker threads (anyio's, up to gamma.app's THREAD_TOKENS) run deep
# validation and PDF code.
WORKER_STACK = 8 << 20

_restart = threading.Event()
_current = None  # the uvicorn.Server serving now, for restart()
_state_path = None


def _now():
    return datetime.now(timezone.utc).isoformat()


def _on_android():
    return sys.platform == "android" or hasattr(sys, "getandroidapilevel")


def _redirect_output(data_dir):
    """Send print, logging and tracebacks to <data dir>/logs/server.log.

    A mobile app has no console that outlives the run, so a file is where a
    failed start can be read: the app shows its tail, and the data directory
    is reachable from the device's files. Line-buffered so the tail is
    current, and appended across launches like the desktop's per-server log,
    each start marked the same way."""
    logs = os.path.join(data_dir, "logs")
    os.makedirs(logs, exist_ok=True)
    stream = open(os.path.join(logs, "server.log"), "a", buffering=1,
                  encoding="utf-8", errors="backslashreplace")
    sys.stdout = sys.stderr = stream
    # The iPad also points the process's descriptors 1 and 2 at the log
    # before Python starts (PythonRuntime.swift). Android leaves them alone:
    # there they are the whole app's, and an emulator's graphics driver
    # writes a line to them for every shader the WebView compiles.
    # A crash in native code (a wheel's extension, the stdlib's C modules)
    # kills the app without a Python traceback; this writes the threads'
    # stacks to the same file first.
    faulthandler.enable(file=stream)
    return stream


def write_state(path, state, detail=""):
    """The supervisor's state file (STATE_FILE), replaced in one step. Never
    raises: the file is advice for the app, and an OSError (a full disk; on
    Windows, a reader holding the file open while it is replaced) must not
    end the supervisor. A few tries, then False."""
    tmp = path + ".tmp"
    for _attempt in range(10):
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump({"state": state, "detail": detail, "at": _now()}, f)
            os.replace(tmp, path)
            return True
        except OSError:
            time.sleep(0.01)
    return False


def supervise(make_server, state_path, log):
    """Run the server ``make_server()`` returns (a uvicorn.Server: ``run()``
    blocks while it serves, ``should_exit`` stops it, ``started`` says it
    listened, a bind that failed is a SystemExit), and when it stops, run a
    new one once ``restart()`` is called. Never returns. A stop nobody
    asked for is written to the state file as "stopped", with why; one a
    restart asked for is not, since the next server starts at once."""
    global _current, _state_path
    _state_path = state_path
    while True:
        _restart.clear()
        write_state(state_path, "starting")
        server = _current = make_server()
        try:
            server.run()
            detail = "the server stopped" if getattr(server, "started", True) else "the server did not start"
        except SystemExit:
            # uvicorn could not listen (the port is taken): it logged the error
            detail = "the server could not listen on its port; the log says why"
        except BaseException as error:  # noqa: BLE001 - whatever ends the server is reported, not raised
            log.write(traceback.format_exc())
            detail = f"the server failed: {error}"
        _current = None
        if not _restart.is_set():
            write_state(state_path, "stopped", detail)
            log.write(f"\n--- {_now()} {detail}; waiting for a restart ---\n")
        _restart.wait()
        log.write(f"\n--- {_now()} restart ---\n")


def restart():
    """Asked by the app (PythonRuntime.restart), from a thread that holds the
    GIL for this call: a server still running is told to exit, and the
    supervisor runs a new one. The state file says "starting" before this
    returns, so the app never reads the old failure as the new start's."""
    if _state_path:
        write_state(_state_path, "starting")
    server = _current
    if server is not None:
        server.should_exit = True
    _restart.set()


def configure(env=None):
    """Make ``env`` (a dict, a JSON object, or None) the server's
    environment, over the process's own, with the mobile apps' defaults
    under it. gamma/config.py reads the environment at import time, so this
    runs before anything imports gamma."""
    if isinstance(env, (str, bytes)):
        env = json.loads(env)
    for name, value in (env or {}).items():
        os.environ[str(name)] = str(value)
    # The app the server ships in brings its updates (its store, or the
    # release it was installed from), so the admin dashboard asks GitHub
    # nothing and gives no pull instructions (gamma/version.py UPDATES).
    os.environ.setdefault("GAMMA_UPDATES", "app")
    os.environ.setdefault("GAMMA_UPDATE_CHECK", "off")
    # The cryptography wheel for iOS links OpenSSL 3 without its legacy
    # provider and refuses to import until told that is expected; the server
    # needs none of the legacy algorithms (it uses Fernet: AES and HMAC), so
    # a wheel that has the provider loses nothing either.
    os.environ.setdefault("CRYPTOGRAPHY_OPENSSL_NO_LEGACY", "1")
    # tempfile keeps the directory it found first, and one looked for before
    # TMPDIR was set would be used for good (on Android, none: there is no
    # /tmp, and the working directory is the read-only root).
    tempfile = sys.modules.get("tempfile")
    if tempfile is not None:
        tempfile.tempdir = None


class UntaggedExtensions:
    """An import finder for extension modules whose file names carry the
    Python version but not the platform: Flet's Android PyMuPDF ships
    ``pymupdf/_extra.cpython-313.so`` and ``_mupdf.cpython-313.so``, which
    neither Python's suffixes on Android (``.cpython-313-<arch>-linux-android.so``,
    ``.abi3.so``, ``.so``) nor Chaquopy's importer, built from them, match.
    It is consulted last (``sys.meta_path``), only for a name nothing else
    found, and only in folders that exist on disk (every package is extracted,
    android/app/build.gradle.kts). The library such an extension needs beside
    it (libmupdf.so) is found through its own RUNPATH of ``$ORIGIN``."""

    SUFFIX = f".cpython-{sys.version_info.major}{sys.version_info.minor}.so"

    @classmethod
    def find_spec(cls, fullname, path=None, target=None):
        import importlib.machinery
        import importlib.util
        name = fullname.rpartition(".")[2]
        for folder in path if path is not None else sys.path:
            candidate = os.path.join(str(folder), name + cls.SUFFIX)
            if os.path.isfile(candidate):
                loader = importlib.machinery.ExtensionFileLoader(fullname, candidate)
                return importlib.util.spec_from_file_location(fullname, candidate, loader=loader)
        return None


def _load_wheel_libraries():
    """On Android, load the shared libraries Flet's wheels keep in an
    ``opt/lib`` folder of their own (Pillow's libjpeg, libtiff, libwebp,
    libfreetype and libc++_shared, from the flet-lib* wheels) before
    anything imports the extensions that need them. Android's linker looks
    for a library an extension names only in the app's own native folder
    and among the libraries already loaded, never beside the extension, so
    each is loaded here by its path, and a library loaded once satisfies
    every later extension that names it. The order they need each other in
    is not known, so a library that fails is tried again after the others,
    until a pass loads nothing more; one that still fails is left for the
    import that needs it to report."""
    if not _on_android():
        return
    if UntaggedExtensions not in sys.meta_path:
        sys.meta_path.append(UntaggedExtensions)
    import ctypes
    pending = []
    for entry in sys.path:
        folder = os.path.join(entry, "opt", "lib")
        if os.path.isdir(folder):
            pending += sorted(os.path.join(folder, name) for name in os.listdir(folder) if ".so" in name)
    while pending:
        failed = []
        for path in pending:
            try:
                ctypes.CDLL(path, mode=ctypes.RTLD_GLOBAL)
            except OSError:
                failed.append(path)
        if len(failed) == len(pending):
            return
        pending = failed


def _thread_stacks():
    """Give the threads started from here on Android WORKER_STACK."""
    if _on_android():
        threading.stack_size(WORKER_STACK)


def main():
    data_dir = os.environ["GAMMA_DATA_DIR"]
    port = int(os.environ["GAMMA_PORT"])
    log = _redirect_output(data_dir)
    log.write(f"\n--- {_now()} start port {port} ---\n")

    # The built web app sits beside this file's folder in the iPad's bundle
    # (python/frontend_dist next to python/app). The apps set the path; this
    # only covers a runtime started without it, as the desktop entry does.
    static = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "frontend_dist")
    if os.path.isdir(static):
        os.environ.setdefault("GAMMA_STATIC_DIR", static)

    # Before the first thread the server starts: asyncio's and anyio's.
    _thread_stacks()
    _load_wheel_libraries()

    # gamma/config.py reads the environment at import time, so the import
    # comes after the environment is final, and after the redirect so an
    # import error lands in the log.
    import uvicorn
    from gamma.app import app

    # Loopback only: the web view in the app is the one client. uvicorn
    # installs SIGINT/SIGTERM handlers because this is Python's main thread
    # (the one that initialized it); both systems end apps with SIGKILL, so
    # they change nothing there. The app's lifespan (gamma/app.py: the
    # sweeps, the backup schedule) runs with each server and ends with it.
    def make_server():
        return uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="info"))

    supervise(make_server, os.path.join(data_dir, "logs", STATE_FILE), log)


def run(env=None):
    """The apps' entry: ``configure(env)``, then ``main()``, for good.
    Whatever fails, the traceback goes to the server's log by this function
    itself, since a failure before ``main`` redirects the output (an import,
    a missing variable) would otherwise go to a console no one sees. An
    uncaught SystemExit (uvicorn's way of saying it could not start) would
    end the process, which is the whole app: it becomes an ordinary error,
    logged and reported like the rest."""
    try:
        configure(env)
        main()
    except BaseException:  # noqa: BLE001 - a SystemExit too, see above
        text = traceback.format_exc()
        logs = os.path.join(os.environ.get("GAMMA_DATA_DIR", "."), "logs")
        try:
            os.makedirs(logs, exist_ok=True)
            with open(os.path.join(logs, "server.log"), "a", encoding="utf-8", errors="backslashreplace") as log:
                log.write("\n--- the server did not start ---\n" + text)
        except OSError:
            pass
        sys.stderr.write(text)
        raise RuntimeError("the server exited") from None


if __name__ == "__main__":
    run()
