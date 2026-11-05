// The pure half of "Copy image": which pictures are vectors, and the SVG a
// vector picture is copied as; and what "Copy as SVG" (copySvg) puts on the
// clipboard. jsdom supplies the DOMParser and serializer; the clipboard,
// the image and the canvas are stubs.
import assert from "node:assert/strict";
import { test } from "node:test";
import { JSDOM } from "jsdom";

globalThis.window = new JSDOM("").window;
const { copySvg, isVectorPicture, shownSvg } = await import("../src/shared/lib/copyImage.js");

const parse = (svg) => new window.DOMParser().parseFromString(svg, "image/svg+xml").documentElement;

test("vector pictures: an inline svg, an SVG data: URL or file; bitmaps are not", () => {
  assert.equal(isVectorPicture({ localName: "svg" }), true);
  assert.equal(isVectorPicture({ localName: "img", src: "data:image/svg+xml;charset=utf-8,%3Csvg%3E" }), true);
  assert.equal(isVectorPicture({ localName: "img", src: "http://h/api/uploads/ab12.svg?ws=w1" }), true);
  assert.equal(isVectorPicture({ localName: "img", currentSrc: "http://h/a.SVG", src: "http://h/a.SVG" }), true);
  assert.equal(isVectorPicture({ localName: "img", src: "http://h/api/uploads/ab12.png" }), false);
  assert.equal(isVectorPicture({ localName: "img", src: "http://h/svg/ab12.jpg" }), false);
  assert.equal(isVectorPicture(null), false);
});

test("the copy takes the shown size; a fixed size without a viewBox gets one, so it scales", () => {
  const root = parse(shownSvg('<svg xmlns="http://www.w3.org/2000/svg" width="320px" height="200"><rect width="5" height="5"/></svg>',
    { width: 160.4, height: 100 }));
  assert.equal(root.getAttribute("viewBox"), "0 0 320 200");
  assert.equal(root.getAttribute("width"), "160");
  assert.equal(root.getAttribute("height"), "100");
  assert.equal(root.querySelector("rect").getAttribute("width"), "5", "the drawing is as it was");
});

test("a viewBox stays; Mermaid's width=100% and max-width give way to the shown size", () => {
  const root = parse(shownSvg('<svg xmlns="http://www.w3.org/2000/svg" width="100%" viewBox="-8 -8 452 300" style="max-width: 452px; font-family: serif"><g/></svg>',
    { width: 452, height: 300, background: "rgb(30, 30, 30)" }));
  assert.equal(root.getAttribute("viewBox"), "-8 -8 452 300");
  assert.equal(root.getAttribute("width"), "452");
  assert.equal(root.style.maxWidth, "");
  assert.equal(root.style.fontFamily, "serif", "other style stays");
  assert.equal(root.style.backgroundColor, "rgb(30, 30, 30)", "the background it is shown on");
});

test("a relative size without a viewBox is not given one", () => {
  const root = parse(shownSvg('<svg xmlns="http://www.w3.org/2000/svg" width="50%" height="10em"/>', { width: 40, height: 20 }));
  assert.equal(root.hasAttribute("viewBox"), false);
  assert.equal(root.getAttribute("width"), "40");
});

test("markup that is not an SVG document is refused", () => {
  assert.throws(() => shownSvg("<html/>", { width: 1, height: 1 }), /Not an SVG document/);
  assert.throws(() => shownSvg("<svg", { width: 1, height: 1 }), /Not an SVG document/);
});

// A secure page with the async clipboard: `supportsSvg` whether the browser
// writes image/svg+xml, `fails` a write that is refused. Every write lands
// in `writes` (its items' flavours); images decode at once, and a canvas
// gives a PNG blob that names its size and what was drawn on it.
function clipboard(t, { supportsSvg = false, fails = false } = {}) {
  const writes = [];
  class ClipboardItem {
    constructor(items) { this.items = items; }
    static supports(type) { return type !== "image/svg+xml" || supportsSvg; }
  }
  const saved = { navigator: Object.getOwnPropertyDescriptor(globalThis, "navigator"), Image: globalThis.Image, document: globalThis.document };
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: { clipboard: {
    write: async (items) => { writes.push(items); if (fails) throw new Error("NotAllowedError"); },
  } } });
  window.isSecureContext = true;
  window.ClipboardItem = ClipboardItem;
  globalThis.Image = class { decode() { return Promise.resolve(); } };
  globalThis.document = { createElement: () => {
    const canvas = { drawn: null, getContext: () => ({ drawImage: (image) => { canvas.drawn = image.src; } }),
      toBlob: (done, type) => done(new Blob([JSON.stringify({ width: canvas.width, height: canvas.height, src: canvas.drawn })], { type })) };
    return canvas;
  } };
  t.after(() => {
    Object.defineProperty(globalThis, "navigator", saved.navigator);
    delete window.isSecureContext;
    delete window.ClipboardItem;
    globalThis.Image = saved.Image;
    globalThis.document = saved.document;
  });
  return writes;
}

const MARKUP = '<svg xmlns="http://www.w3.org/2000/svg" width="50.5" height="20" viewBox="0 0 50.5 20"><path d="M0,0 L10,10"/></svg>';

test("copy as SVG: one item in the click, a PNG at twice the size, the markup as text, no SVG where the browser takes none", async (t) => {
  const writes = clipboard(t);
  const done = copySvg(MARKUP, { width: 50.5, height: 20 });
  assert.equal(writes.length, 1, "written before anything is awaited, so Safari keeps the gesture");
  assert.equal(await done, true);
  const { items } = writes[0][0];
  assert.deepEqual(Object.keys(items).sort(), ["image/png", "text/plain"]);
  assert.equal(await items["text/plain"].text(), MARKUP);
  const png = await items["image/png"];
  assert.equal(png.type, "image/png");
  const drawn = JSON.parse(await png.text());
  assert.deepEqual([drawn.width, drawn.height], [101, 40]);
  assert.equal(drawn.src, `data:image/svg+xml;charset=utf-8,${encodeURIComponent(MARKUP)}`, "the markup drawn");
});

test("copy as SVG: the SVG flavour too where the browser writes one", async (t) => {
  const writes = clipboard(t, { supportsSvg: true });
  assert.equal(await copySvg(MARKUP, { width: 50.5, height: 20 }), true);
  const { items } = writes[0][0];
  assert.deepEqual(Object.keys(items).sort(), ["image/png", "image/svg+xml", "text/plain"]);
  assert.equal(items["image/svg+xml"].type, "image/svg+xml");
  assert.equal(await items["image/svg+xml"].text(), MARKUP);
});

test("copy as SVG: false when the write is refused, and without the async clipboard", async (t) => {
  clipboard(t, { fails: true });
  assert.equal(await copySvg(MARKUP, { width: 50.5, height: 20 }), false);
  delete window.isSecureContext;
  assert.equal(await copySvg(MARKUP, { width: 50.5, height: 20 }), false, "not a secure context");
});
