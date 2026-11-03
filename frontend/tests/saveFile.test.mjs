// Saving a file (src/shared/lib/saveFile.js): the browser's <a download>,
// or, under a native host that declares `save`, the host's messages: a blob
// in base64 chunks (save, saveData, saveDone), a URL by itself (saveUrl).
import test from "node:test";
import assert from "node:assert/strict";

// host.js reads the window once, at its first question: one host for the
// whole file, whose `save` flag and `post` each case sets. Every post goes
// through JSON, as the Android app's message port carries it.
const native = { platform: "android", version: "0.1.0", save: true };
const posted = [];
let answer = () => ({});
native.post = (msg) => {
  const wire = JSON.parse(JSON.stringify(msg));
  assert.deepEqual(wire, msg, `${msg.type} survives the port as JSON`);
  posted.push(wire);
  try {
    return Promise.resolve(JSON.parse(JSON.stringify(answer(wire) ?? null)));
  } catch (err) {
    return Promise.reject(err);
  }
};
// The page's anchors: what each click asked the browser to download.
const clicked = [];
globalThis.location = { href: "http://127.0.0.1:8123/?ws=w1" };
globalThis.window = { gammaNative: native, navigator: {}, matchMedia: () => ({ matches: false }) };
globalThis.document = {
  documentElement: { setAttribute: () => {} },
  body: { appendChild: (a) => { a.attached = true; } },
  createElement: (tag) => {
    assert.equal(tag, "a");
    const a = { attached: false, click() { clicked.push({ href: a.href, download: a.download, attached: a.attached }); }, remove() { a.attached = false; } };
    return a;
  },
};
const { SAVE_CHUNK, dispositionName, hostSaves, saveBlob, saveUrl } = await import("../src/shared/lib/saveFile.js");

// The host's side of a save, as the Android app keeps one (shell/Saves.kt):
// files by id, each chunk appended at its offset, refused or kept whole.
function saves({ refuse = false, failAt = null, saved = true } = {}) {
  const open = new Map();
  const done = [];
  let next = 0;
  answer = (msg) => {
    if (msg.type === "save") {
      if (refuse) return {};
      const id = `S${++next}`;
      open.set(id, { name: msg.name, mime: msg.mime, size: msg.size, parts: [] });
      return { id };
    }
    if (msg.type === "saveData") {
      const f = open.get(msg.id);
      if (!f) throw new Error("no such save");
      if (failAt != null && msg.offset >= failAt) throw new Error("the disk is full");
      const have = f.parts.reduce((n, p) => n + Buffer.from(p, "base64").length, 0);
      if (msg.offset !== have) throw new Error(`a gap at ${msg.offset}`);
      f.parts.push(msg.data);
      return {};
    }
    if (msg.type === "saveDone") {
      const f = open.get(msg.id);
      open.delete(msg.id);
      if (f && !msg.cancel) done.push(f);
      return { saved: saved && !!f && !msg.cancel };
    }
    return {};
  };
  return { open, done };
}
const reset = () => { posted.length = 0; clicked.length = 0; native.save = true; };
const bytes = (n) => Uint8Array.from({ length: n }, (_, i) => (i * 7 + (i >> 9)) & 0xff);

test("a chunk is just under 2 MiB and a multiple of 3, so only the last chunk's base64 is padded", () => {
  assert.equal(SAVE_CHUNK % 3, 0);
  assert.ok(SAVE_CHUNK <= 2 * 1024 * 1024 && SAVE_CHUNK > 2 * 1024 * 1024 - 3);
});

test("under a host that saves: a 5 MiB blob goes over in three chunks at their offsets, each base64 on its own, and comes back whole", async () => {
  reset();
  const host = saves();
  const data = bytes(5 * 1024 * 1024);
  assert.equal(hostSaves(), true);
  assert.equal(await saveBlob(new Blob([data], { type: "application/pdf" }), "Paper title.pdf"), true);
  assert.deepEqual(posted[0], { type: "save", name: "Paper title.pdf", mime: "application/pdf", size: data.length });
  const chunks = posted.filter((m) => m.type === "saveData");
  assert.deepEqual(chunks.map((m) => [m.id, m.offset]), [["S1", 0], ["S1", SAVE_CHUNK], ["S1", 2 * SAVE_CHUNK]]);
  assert.ok(chunks.every((m) => typeof m.data === "string"), "base64 text, never numbers");
  assert.ok(chunks.slice(0, -1).every((m) => !m.data.endsWith("=")), "no padding before the last chunk");
  assert.deepEqual(posted.at(-1), { type: "saveDone", id: "S1" });
  const [file] = host.done;
  assert.deepEqual([file.name, file.mime, file.size], ["Paper title.pdf", "application/pdf", data.length]);
  const decoded = Buffer.concat(file.parts.map((p) => Buffer.from(p, "base64")));
  assert.ok(decoded.equals(Buffer.from(data)), "each chunk decoded alone reassembles the bytes");
  assert.ok(Buffer.from(file.parts.join(""), "base64").equals(Buffer.from(data)), "and so does their text joined");
  assert.deepEqual(clicked, [], "no anchor");
});

test("an empty blob and a small one: a save, its data, its end", async () => {
  reset();
  const host = saves();
  assert.equal(await saveBlob(new Blob([], { type: "text/markdown" }), "empty.md"), true);
  assert.deepEqual(posted.map((m) => m.type), ["save", "saveDone"], "no data for no bytes");
  posted.length = 0;
  assert.equal(await saveBlob(new Blob(["<svg/>"], { type: "image/svg+xml;charset=utf-8" }), "handwriting.svg"), true);
  assert.deepEqual(posted, [
    { type: "save", name: "handwriting.svg", mime: "image/svg+xml;charset=utf-8", size: 6 },
    { type: "saveData", id: "S2", offset: 0, data: Buffer.from("<svg/>").toString("base64") },
    { type: "saveDone", id: "S2" },
  ]);
  assert.deepEqual(host.done.map((f) => f.size), [0, 6]);
  // a nameless blob still names its keys: the host picks a name
  posted.length = 0;
  await saveBlob(new Blob(["x"]));
  assert.deepEqual(posted[0], { type: "save", name: "", mime: "", size: 1 });
});

test("a host that refuses the file: false, and no data posted", async () => {
  reset();
  saves({ refuse: true });
  assert.equal(await saveBlob(new Blob([bytes(10)]), "big.zip"), false);
  assert.deepEqual(posted.map((m) => m.type), ["save"]);
});

test("a host that stops answering partway: the save is called off and the error thrown", async () => {
  reset();
  const host = saves({ failAt: SAVE_CHUNK });
  await assert.rejects(saveBlob(new Blob([bytes(SAVE_CHUNK + 10)]), "half.pdf"), /the disk is full/);
  await new Promise((r) => setTimeout(r));
  assert.deepEqual(posted.map((m) => [m.type, m.offset ?? null, m.cancel ?? null]),
    [["save", null, null], ["saveData", 0, null], ["saveData", SAVE_CHUNK, null], ["saveDone", null, true]]);
  assert.equal(host.open.size, 0, "the host let it go");
  assert.deepEqual(host.done, [], "nothing kept");
});

test("a host that could not keep the finished file: false", async () => {
  reset();
  saves({ saved: false });
  assert.equal(await saveBlob(new Blob(["abc"]), "a.txt"), false);
});

test("a URL under a host: the host fetches it, the address made absolute; a data: URL is read here and goes as a blob", async () => {
  reset();
  const host = saves();
  assert.equal(await saveUrl("/api/jobs/j1/download", "Export.zip"), true);
  assert.deepEqual(posted, [{ type: "saveUrl", url: "http://127.0.0.1:8123/api/jobs/j1/download", name: "Export.zip" }]);
  posted.length = 0;
  await saveUrl("http://127.0.0.1:8123/api/uploads/w1/a.png?ws=w1");
  assert.deepEqual(posted, [{ type: "saveUrl", url: "http://127.0.0.1:8123/api/uploads/w1/a.png?ws=w1", name: "" }], "no name: the host takes the server's or the URL's");
  posted.length = 0;
  assert.equal(await saveUrl("data:image/png;base64,iVBORw0KGgo=", "picture.png"), true);
  assert.deepEqual(posted.map((m) => m.type), ["save", "saveData", "saveDone"]);
  assert.deepEqual(posted[0], { type: "save", name: "picture.png", mime: "image/png", size: 8 });
  assert.equal(Buffer.concat(host.done.at(-1).parts.map((p) => Buffer.from(p, "base64"))).toString("hex"), "89504e470d0a1a0a");
  assert.deepEqual(clicked, []);
});

test("in a browser (no host save): an anchor downloads, the blob's URL let go 2 s later", async (t) => {
  reset();
  native.save = false;
  saves();
  const made = [];
  const revoked = [];
  const { createObjectURL, revokeObjectURL } = URL;
  URL.createObjectURL = (blob) => { made.push(blob); return `blob:http://127.0.0.1:8123/${made.length}`; };
  URL.revokeObjectURL = (href) => { revoked.push(href); };
  t.mock.timers.enable({ apis: ["setTimeout"] });
  try {
    assert.equal(hostSaves(), false);
    const blob = new Blob(["# note"], { type: "text/markdown" });
    assert.equal(await saveBlob(blob, "note.md"), true);
    assert.deepEqual(clicked, [{ href: "blob:http://127.0.0.1:8123/1", download: "note.md", attached: true }], "a link in the page, clicked");
    assert.equal(made[0], blob);
    t.mock.timers.tick(1999);
    assert.deepEqual(revoked, []);
    t.mock.timers.tick(1);
    assert.deepEqual(revoked, ["blob:http://127.0.0.1:8123/1"]);
    assert.equal(await saveUrl("/api/admin/backups/b1/download", "gamma-backup-b1.zip"), true);
    assert.deepEqual(clicked.at(-1), { href: "/api/admin/backups/b1/download", download: "gamma-backup-b1.zip", attached: true });
    await saveUrl("/api/uploads/w1/a.png");
    assert.equal(clicked.at(-1).download, "", "the browser names it");
    assert.deepEqual(posted, [], "nothing posted to the host");
  } finally {
    URL.createObjectURL = createObjectURL;
    URL.revokeObjectURL = revokeObjectURL;
  }
});

test("Content-Disposition names the file: its UTF-8 form first, then the plain one, else the fallback", () => {
  assert.equal(dispositionName(`attachment; filename="Paper.pdf"; filename*=UTF-8''%E7%AC%94%E8%AE%B0.pdf`, "x"), "笔记.pdf");
  assert.equal(dispositionName('attachment; filename="Paper title.pdf"', "x"), "Paper title.pdf");
  assert.equal(dispositionName("attachment; filename=export.zip", "x"), "export.zip");
  assert.equal(dispositionName(`attachment; filename="plain.md"; filename*=UTF-8''%E7%AC`, "x"), "plain.md", "a malformed UTF-8 name falls back to the plain one");
  assert.equal(dispositionName("attachment", "fallback.md"), "fallback.md");
  assert.equal(dispositionName(null, "fallback.md"), "fallback.md");
  assert.equal(dispositionName(""), "");
});
