// Saving a file the person asked for: the browser's download (an
// <a download>), or, under a native host that declares `save`
// (shared/lib/host.js, docs/dev/ipad.md "The host contract"), the host's.
// A web view's download listener can neither read a blob: URL nor learn an
// <a download> name, so the Android app takes the file over the bridge: a
// blob in chunks, base64 each (`save`, `saveData`, `saveDone`), a URL by
// itself (`saveUrl`), which the host fetches with the page's cookies.
import { nativeHost, postToHost } from "./host.js";

// Raw bytes per saveData: just under 2 MiB and a multiple of 3, so only the
// last chunk's base64 can end in padding: the host may decode each chunk or
// join them.
export const SAVE_CHUNK = 2097150;

export const hostSaves = () => Boolean(nativeHost()?.save);

// The name a response asks to be saved as: Content-Disposition's UTF-8
// filename* first, then its filename, else `fallback`.
export function dispositionName(header, fallback = "") {
  const cd = header || "";
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  if (star) {
    try { return decodeURIComponent(star[1].trim()); } catch { /* malformed: the plain name */ }
  }
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  return plain ? plain[1].trim() : fallback;
}

function base64(bytes) {
  if (typeof bytes.toBase64 === "function") return bytes.toBase64();
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

function download(href, name) {
  const a = document.createElement("a");
  a.href = href;
  a.download = name || "";
  document.body.appendChild(a);
  a.click();
  a.remove();
}

// Save `blob` as `name`. Resolves true once the browser or the host has it,
// false when the host refused it (no room, too big); rejects when the host
// stopped answering partway, after telling it to drop what it has.
export async function saveBlob(blob, name) {
  if (!hostSaves()) {
    const href = URL.createObjectURL(blob);
    download(href, name);
    setTimeout(() => URL.revokeObjectURL(href), 2000);
    return true;
  }
  const id = (await postToHost({ type: "save", name: name || "", mime: blob.type || "", size: blob.size }))?.id;
  if (typeof id !== "string" || !id) return false;
  try {
    for (let offset = 0; offset < blob.size; offset += SAVE_CHUNK) {
      const bytes = new Uint8Array(await blob.slice(offset, offset + SAVE_CHUNK).arrayBuffer());
      await postToHost({ type: "saveData", id, offset, data: base64(bytes) });
    }
  } catch (err) {
    postToHost({ type: "saveDone", id, cancel: true }).catch(() => {});
    throw err;
  }
  return (await postToHost({ type: "saveDone", id }))?.saved !== false;
}

// Save what `url` serves, as `name`. In a browser the response's
// Content-Disposition wins over `name`; a host names the file `name` when
// one is given, else by the Content-Disposition (the Android shell's
// SaveNames), so a caller whose file the server names passes none. Under a
// host a blob: or data: URL is read here and goes over as a blob; any other
// the host fetches itself, so a large export never passes through the page.
export async function saveUrl(url, name = "") {
  if (!hostSaves()) {
    download(url, name);
    return true;
  }
  const abs = new URL(url, globalThis.location?.href);
  if (abs.protocol === "blob:" || abs.protocol === "data:") return saveBlob(await (await fetch(abs.href)).blob(), name);
  await postToHost({ type: "saveUrl", url: abs.href, name: name || "" });
  return true;
}

// The onClick of an <a download> (a file chip's link, an image's Download)
// under a host that saves: the link's own download is stopped and the host
// fetches `url` as `name`. Undefined in a browser, where the link downloads
// by itself.
export const saveLinkClick = (url, name = "") => (hostSaves()
  ? (e) => { e.preventDefault(); saveUrl(url, name).catch(() => {}); }
  : undefined);
