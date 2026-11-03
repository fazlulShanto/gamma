// The Android app's `gammaShell` for its chrome pages, desktop/ui/bar.html
// and launcher.html, which it serves from
// https://appassets.androidplatform.net/assets/chrome/ (docs/dev/android.md
// "The chrome"). It is the object the desktop's preload.js and the iPad's
// Chrome.swift declare: a platform, the shell's methods as promises, and
// onState for the state the shell pushes. The app injects this file at
// document start for that origin only, beside the message port
// `gammaShellHost`, which carries strings:
//   to native    {hello: true} once; {id, method, args}
//   from native  {reply, value} or {reply, error}; {state}
(() => {
  if (window.top !== window) return;
  const port = window.gammaShellHost;
  if (!port || window.gammaShell) return;
  const listeners = new Set();
  const pending = new Map();
  let next = 0;
  const say = (m) => port.postMessage(JSON.stringify(m));
  const post = (method, args) => new Promise((resolve, reject) => {
    const id = ++next;
    pending.set(id, { resolve, reject });
    try {
      say({ id, method, args });
    } catch (err) {
      pending.delete(id);
      reject(err);
    }
  });
  const shell = {
    platform: "android",
    onState(cb) { listeners.add(cb); return () => listeners.delete(cb); },
    _state(s) { for (const cb of listeners) cb(s); },
  };
  for (const m of ["state", "list", "open", "openWorkspace", "launcher", "reload", "barExpand", "hideBar",
                   "addRemote", "rename", "setUrl", "remove", "revealData", "revealLog", "copyAddress"]) {
    shell[m] = (...args) => post(m, args);
  }
  port.addEventListener("message", (event) => {
    let m;
    try { m = JSON.parse(event.data); } catch { return; }
    if (!m || typeof m !== "object") return;
    if (m.reply != null) {
      const p = pending.get(m.reply);
      if (!p) return;
      pending.delete(m.reply);
      if (typeof m.error === "string") p.reject(new Error(m.error));
      else p.resolve(m.value);
    } else if (m.state) {
      shell._state(m.state);
    }
  });
  window.gammaShell = shell;
  say({ hello: true });
})();
