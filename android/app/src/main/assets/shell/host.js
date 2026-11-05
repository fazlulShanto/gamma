// The Android app's host contract, declared in the server's page before its
// scripts run (docs/dev/android.md "The bridge"). The app injects this file
// at document start (WebViewCompat.addDocumentStartJavaScript, for the
// server's origin only) with the placeholder below replaced by its HostInfo
// JSON, beside the message port `gammaAndroidHost`
// (WebViewCompat.addWebMessageListener), which carries strings and pairs
// nothing. Over it this lays the iPad's two directions
// (frontend/src/shared/lib/host.js):
//   window.gammaNative.post(msg)  a promise of native's reply, which
//                                 postToHost prefers to WebKit's handler;
//   window.gammaNative.receive    native's messages, queued until host.js
//                                 takes over the stub, each answer sent back.
// The wire, one JSON string per message:
//   to native    {hello: true} once, so native holds this document's reply
//                proxy; {id, msg}; {result, value} or {result, error}
//   from native  {reply, value} or {reply, error}; {send, msg}
// frontend/tests/e2e/scenarios/android.mjs runs this file over a stand-in
// port, so the e2e suite exercises the real script.
(() => {
  if (window.top !== window) return;
  const port = window.gammaAndroidHost;
  if (!port || window.gammaNative) return;
  const info = __GAMMA_HOST_INFO__;
  const pending = new Map();
  let next = 0;
  const queue = [];
  const say = (m) => port.postMessage(JSON.stringify(m));
  const native = Object.assign(info, {
    queue,
    receive: (msg) => new Promise((resolve, reject) => { queue.push({ msg, resolve, reject }); }),
    post: (msg) => new Promise((resolve, reject) => {
      const id = ++next;
      pending.set(id, { resolve, reject });
      try {
        say({ id, msg });
      } catch (err) {
        pending.delete(id);
        reject(err);
      }
    }),
  });
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
    } else if (m.send != null) {
      // native.receive is read now, not captured: once host.js has loaded it
      // is the page's own, before that the queueing stub above.
      Promise.resolve()
        .then(() => native.receive(m.msg))
        .then(
          (value) => say({ result: m.send, value: value === undefined ? null : value }),
          (err) => say({ result: m.send, error: String((err && err.message) || err) }),
        );
    }
  });
  window.gammaNative = native;
  document.documentElement?.setAttribute("data-host", info.platform);
  say({ hello: true });
})();
