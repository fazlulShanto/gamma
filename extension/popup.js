import {
  api, chooseWorkspace, currentWorkspace, defaultFolder, folderByPath, getSettings, login, normalizeServer,
  originPattern, removeServer, setSettings,
} from "./api.js";
import { renderServerList } from "./serverList.js";
import { connectPublisher, describeSession, publisherHost, publisherRoot } from "./publisherSessions.js";
import { icon, menuRow, placeMenu, workspacePicker } from "./ui.js";

const $ = (id) => document.getElementById(id);
const show = (id, on = true) => $(id).classList.toggle("hidden", !on);

let tab = null;
let state = null;
let workspaceSelect = null;                // the workspace picker (ui.js); "" = the account's default
let folderField = null;                    // the folder field (chipField): one chip, none = the library root
let labelField = null;                     // the labels field (chipField): a chip per label
let libraryFolders = [];                   // the library's folders and label names
let labelNames = [];                       // (GET /api/library/folders), suggested as you type
let pub = null;                            // publisher sessions: the worker's `publisher-status` answer
let cookieTimer = 0;                       // the cookie button's "done" flash
let serverBusy = false;
let serverTrigger = null;

function closeServerMenu(focus = false) {
  show("server-menu", false);
  if (serverTrigger) {
    serverTrigger.setAttribute("aria-expanded", "false");
    if (focus) serverTrigger.focus();
  }
}

async function openServerMenu(trigger) {
  if (serverBusy) return;
  if (serverTrigger === trigger && !$("server-menu").classList.contains("hidden")) {
    closeServerMenu(true);
    return;
  }
  closeServerMenu();
  closeDrawer();
  serverTrigger = trigger;
  const settings = await getSettings();
  renderServerList($("server-list"), settings, {
    select: (origin) => changeServer(origin),
    remove: (origin) => changeServer(origin, true),
  });
  trigger.closest(".foot").before($("server-menu"));
  trigger.setAttribute("aria-expanded", "true");
  show("server-menu");
  show("server-error", false);
  ($("server-list").querySelector('[aria-pressed="true"]') || $("server-list").querySelector("button") || $("manage-servers")).focus();
  trigger.scrollIntoView({ block: "nearest" });
}

async function changeServer(origin, forget = false) {
  if (serverBusy) return;
  serverBusy = true;
  for (const button of $("server-menu").querySelectorAll("button")) button.disabled = true;
  try {
    if (forget) await removeServer(origin);
    else {
      const granted = await chrome.permissions.request({ origins: [originPattern(origin)] });
      if (!granted) throw new Error("Permission to talk to that server was declined.");
      await setSettings({ server: origin });
    }
    closeServerMenu();
    closeDrawer();
    $("login-user").value = "";
    $("login-pass").value = "";
    show("login-msg", false);
    await refresh(true);
    document.querySelector('[id^="view-"]:not(.hidden) [data-server-switch]')?.focus();
  } catch (err) {
    $("server-error").textContent = err.message;
    show("server-error");
  } finally {
    serverBusy = false;
    for (const button of $("server-menu").querySelectorAll("button")) button.disabled = false;
  }
}

async function send(msg) {
  const r = await chrome.runtime.sendMessage(msg);
  if (!r) throw new Error("no response from the extension");
  if (!r.ok) { const e = new Error(r.error); e.status = r.status; throw e; }
  return r.result;
}

function hostOf(origin) {
  try { return new URL(origin).host; } catch { return origin; }
}

function openPath(path) {
  send({ type: "open", path }).then(() => window.close());
}

// ---------- views ----------

const VIEWS = ["view-setup", "view-offline", "view-login", "view-main"];
function view(id) { for (const v of VIEWS) show(v, v === id); }

function setConn(cls, title) {
  const el = $("conn");
  el.className = "connDot " + cls;
  el.title = title;
}

function showSetup() {
  view("view-setup");
  $("setup-server").focus();
}

function showOffline(st) {
  view("view-offline");
  $("offline-server").textContent = st.origin;
  // st.error already reads "Can't reach <origin> (…)" (api.js).
  $("offline-msg").textContent = `${st.error || `Can't reach ${st.origin}`}. Is the server running, and the address right?`;
  $("offline-foot").textContent = hostOf(st.origin);
}

function showLogin(st) {
  view("view-login");
  $("login-server").textContent = st.origin;
  $("login-foot").textContent = hostOf(st.origin);
  $("login-user").focus();
}

async function showMain(st) {
  view("view-main");
  const c = st.candidate || { kind: "none" };
  $("foot").textContent = `${hostOf(st.origin)} · ${st.user}`;
  setConn("ok", `Connected to ${st.origin} — signed in as ${st.user}`);

  renderHead(st);

  showExisting(st);
  $("dot").className = "dot " + (st.hit ? "ok" : c.kind === "none" ? "" : "on");
  show("existing", !!st.hit);
  show("found", !st.hit && c.kind !== "none");
  show("nothing", !st.hit && c.kind === "none");
  $("nothing-msg").textContent = nothingMessage(c);
  // A "possible paper" saves the paper its one DOI names; this saves the
  // page around it instead (a news story, a blog post).
  show("save-page", c.kind === "maybe" && !st.saving);
  show("clip", !c.is_pdf_tab);
  show("result", false);
  if (st.saving) { show("progress"); $("progress-text").textContent = st.saving; } else show("progress", false);
  if (st.error) { $("result").className = "msg err"; $("result").textContent = st.error; show("result"); }
  show("copy-log", !!st.log);

  await fillPickers(st);
  $("pub-auto").checked = st.settings.autoRefreshSessions !== false;
  await loadPublisher(st);
}

// Why a page shows no paper, and what "Save this URL anyway" will keep.
function nothingMessage(c) {
  const anyway = "You can still save the page's URL — Gamma will look for a PDF behind it.";
  if (c.record) return `No PDF or DOI on this page, but its citation tags name a work. Saving keeps the page with that record. ${anyway}`;
  if (c.dois_in_text > 1) return `This page names ${c.dois_in_text >= 10 ? "many" : c.dois_in_text} papers by DOI, so none of them is taken as its own. ${anyway}`;
  return `No paper or PDF detected on this page. ${anyway}`;
}

// The page itself, not the paper its one DOI names: what "Save the web page
// instead" saves (the server makes a page with its web source).
function pageCandidate(c) {
  return { kind: "none", source_url: c.source_url || (tab && tab.url) || "", title: c.title || (tab && tab.title) || "",
           record: c.record || null };
}

// The notice over "Open in Gamma", for a paper the look-up found or a save
// just filed (`saved`, the save's answer). The head already names the paper,
// so the notice says only where it is. With one library there is nothing to
// name; with several, it names the one the workspace row has picked, where
// picking another looks the paper up there. A save's caveat (no PDF) follows
// in amber.
function showExisting(st, saved = null) {
  const where = (st.workspaces || []).length > 1 ? workspaceName(st) : "your library";
  $("existing-where").textContent = saved && !saved.existed ? `Saved to ${where}` : `Already in ${where}`;
  $("existing-note").textContent = saved && saved.note ? ` ${saved.note}` : "";
  $("existing-msg").className = "msg " + (saved && saved.note ? "warn" : "ok");
}

// The library saves go to: the chosen workspace, else the account's default.
function workspaceName(st) {
  const chosen = currentWorkspace(st.settings);
  const found = (st.workspaces || []).find((w) => (chosen ? w.id === chosen : w.default));
  return found ? found.name : "your library";
}

// ---------- publisher sessions: the footer's cookie button + drawer ----------
//
// The button's state names what a click would do on THIS tab: `off` (not a
// journal page — the drawer still lists connected hosts), `ready` (a journal
// host, not connected yet), `connected`, `saving` (the cookie wobbles inside a
// spinning ring while the snapshot uploads), `done` (a green check pops for a
// moment), `err`. Hidden for guests, incognito tabs and older servers.

function cookieState(cls, title) {
  const b = $("cookie-btn");
  b.className = "footBtn cookieBtn " + cls;
  if (title) b.title = title;
}

// What this tab's host means for the drawer: {host, root, session}.
function currentPublisher() {
  if (!pub || !pub.supported) return { host: "", root: "", session: null };
  const host = publisherHost(tab && tab.url, pub.roots);
  return { host, root: publisherRoot(host, pub.roots), session: pub.sessions.find((s) => s.host === host) || null };
}

async function loadPublisher(st, force = false) {
  try { pub = await send({ type: "publisher-status", force }); }
  catch (err) { pub = { supported: false, error: err.message }; }
  renderCookie(st);
}

function renderCookie(st) {
  const usable = pub && pub.supported && !(tab && tab.incognito);
  show("cookie-btn", !!usable);
  if (!usable) { closeDrawer(); return; }
  const { host, session } = currentPublisher();
  const n = pub.sessions.length;
  const count = n ? ` · ${n} connected` : "";
  if (session) cookieState("connected", `${host}: session connected (${describeSession(session)})${count}`);
  else if (host) cookieState("ready", `Connect ${host}'s session to Gamma${count}`);
  else cookieState("off", `Publisher sessions${count}`);
  if (!$("cookie-drawer").classList.contains("hidden")) renderDrawer(st);
}

function renderDrawer(st) {
  const { host, root, session } = currentPublisher();
  const secure = !!pub.secure;
  const stateChip = $("pub-state");
  if (host) {
    $("pub-host").textContent = host;
    stateChip.className = "chip " + (session ? "ok" : "muted");
    stateChip.textContent = session ? "connected" : "not connected";
    $("pub-meta").textContent = session ? describeSession(session)
      : secure ? `Signed in here? Send its cookies to ${hostOf(st.origin)} · ${st.user} so the server can download this journal's PDFs.`
      : "Connecting needs an HTTPS or localhost Gamma server.";
    show("pub-connect", secure);
    $("pub-connect").textContent = session ? "Refresh now" : "Connect this journal";
    $("pub-connect").onclick = () => doConnectPublisher(st, host, root, session);
  } else {
    $("pub-host").textContent = "No journal on this tab";
    stateChip.className = "chip muted hidden";
    $("pub-meta").textContent = `Open an article or PDF on a supported publisher over HTTPS to connect its session. Supported: ${pub.roots.join(", ")}.`;
    show("pub-connect", false);
  }
  stateChip.classList.toggle("hidden", !host);
  // The last automatic refresh for this host, when it failed — a silent
  // success just shows up as a newer "refreshed … ago".
  if (pub.auto && pub.auto.host === host && !pub.auto.ok && session) {
    $("pub-meta").textContent += ` · automatic refresh failed: ${pub.auto.error}`;
  }
  const list = $("pub-list");
  list.replaceChildren();
  for (const s of pub.sessions) {
    if (s.host === host) continue;
    const row = document.createElement("div"); row.className = "pubRow";
    const ic = document.createElement("span"); ic.className = "ctxMenuIcon"; ic.appendChild(icon("cookie"));
    const text = document.createElement("span"); text.className = "pubRowText";
    const h = document.createElement("span"); h.className = "pubRowHost"; h.textContent = s.host;
    const m = document.createElement("span"); m.className = "pubRowMeta"; m.textContent = describeSession(s);
    text.append(h, m);
    const x = document.createElement("button"); x.type = "button"; x.className = "uiClose uiCloseSm"; x.append(icon("x")); x.title = `Disconnect ${s.host}`;
    x.onclick = () => doDisconnectPublisher(st, s.host, x);
    row.append(ic, text, x);
    list.append(row);
  }
  show("pub-list", list.children.length > 0);
}

function openDrawer(st) {
  renderDrawer(st);
  show("cookie-drawer");
  $("cookie-btn").setAttribute("aria-expanded", "true");
  $("cookie-drawer").scrollIntoView({ block: "nearest" });
}

function closeDrawer() {
  show("cookie-drawer", false);
  show("pub-msg", false);
  $("cookie-btn").setAttribute("aria-expanded", "false");
}

function pubMessage(text, error = false) {
  $("pub-msg").className = "msg " + (error ? "err" : "ok");
  $("pub-msg").textContent = text;
}

async function doConnectPublisher(st, host, root, session) {
  const btn = $("pub-connect");
  btn.disabled = true;
  btn.classList.add("busy");
  btn.innerHTML = '<span class="spinner light"></span>' + (session ? "Refreshing…" : "Connecting…");
  cookieState("saving", `Sending ${host}'s cookies to Gamma…`);
  show("pub-msg", false);
  clearTimeout(cookieTimer);
  try {
    // Must be invoked directly from the user's gesture, before other awaits.
    const granted = await chrome.permissions.request({ permissions: ["cookies"] });
    if (!granted) throw new Error("Cookie access was declined. PDF saving still works.");
    await connectPublisher({ tabId: tab.id, host, root, user: st.user, origin: st.origin });
    pub = await send({ type: "publisher-status", force: true });
    cookieState("done", `${host}: session connected`);
    btn.classList.replace("busy", "done");
    btn.innerHTML = ""; btn.append(icon("check", "", 16, 2), document.createTextNode(session ? " Refreshed" : " Connected"));
    pubMessage(session ? "Session refreshed. Gamma has this journal's current cookies." : "Session connected. Gamma can use it for this journal's PDF downloads.");
    cookieTimer = setTimeout(() => { btn.disabled = false; btn.classList.remove("done"); renderCookie(st); renderDrawer(st); }, 1600);
  } catch (err) {
    pubMessage(err.message, true);
    cookieState("err", err.message);
    btn.disabled = false;
    btn.classList.remove("busy");
    cookieTimer = setTimeout(() => renderCookie(st), 1600);
    renderDrawer(st);
    if (err.status === 401) { state = await send({ type: "auth-changed" }); showLogin(state); }
  }
}

async function doDisconnectPublisher(st, host, button) {
  button.disabled = true;
  try {
    await api(`/publisher-sessions/${encodeURIComponent(host)}`, { method: "DELETE", expectedUser: st.user, expectedOrigin: st.origin });
    pub = await send({ type: "publisher-status", force: true });
    renderCookie(st); renderDrawer(st);
    pubMessage(`${host} disconnected — Gamma's copy of its cookies is deleted.`);
  } catch (err) { pubMessage(err.message, true); button.disabled = false; }
}

// The head names the paper on THIS tab: the page's own title (meta tags),
// else the registry record the worker previewed for the detected DOI /
// arXiv id (a PDF tab has no meta tags), else the library page's title,
// else the host. A "possible paper" is the paper its one DOI names, so the
// registry's title goes first there: under the page's own title (a news
// story's) the registry's authors would read as the story's. The identifier
// line shows what the detection rests on, the third line the registry's
// authors · year · venue.
function renderHead(st) {
  const c = st.candidate || { kind: "none" };
  const pv = st.preview || null;
  const kindLabel = { pdf: "PDF", arxiv: "arXiv", doi: "DOI", maybe: "possible paper", none: "" }[c.kind] || "";
  const idText = c.arxiv_id ? `arXiv:${c.arxiv_id}` : c.doi ? `doi:${c.doi}` : "";
  const paperFirst = c.kind === "maybe" && pv && pv.title;
  const title = (paperFirst ? pv.title : c.title || (pv && pv.title)) || (st.hit && st.hit.title)
    || (c.kind === "none" ? (tab && tab.title) || "This page" : hostOf(c.pdf_url || c.source_url));
  $("title").textContent = title;
  const sub = $("sub");
  sub.innerHTML = "";
  if (kindLabel) { const chip = document.createElement("span"); chip.className = "chip" + (c.kind === "maybe" ? " muted" : ""); chip.textContent = kindLabel; sub.appendChild(chip); }
  sub.appendChild(document.createTextNode(idText || (c.pdf_url && c.kind === "pdf" ? (c.is_pdf_tab ? "this tab is a PDF" : "PDF available") : hostOf(c.source_url || ""))));
  if (pv) {
    const authors = pv.authors || [];
    const who = authors.length > 3 ? `${authors[0]} et al.` : authors.join(", ");
    const line = [who, pv.year, pv.venue].filter(Boolean).join(" · ");
    if (line) { const el = document.createElement("div"); el.className = "meta"; el.textContent = line; el.title = authors.join(", "); sub.appendChild(el); }
  }
  // The library page may carry a different title (a stale or wrong metadata
  // record) — say so instead of silently showing it as this paper's.
  const norm = (t) => (t || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const libTitle = st.hit && st.hit.title || "";
  $("existing-as").textContent = libTitle && norm(libTitle) !== norm(title) ? ` as “${libTitle}”` : "";
}

// ---------- the workspace picker, the folder and label fields ----------

// The workspace saves go to, then that library's folders and labels. The
// workspace row, over every view of the main popup, appears only for an
// account with a choice to make: the worker already filtered the list to the
// ones this account can write to.
async function fillPickers(st) {
  const settings = st.settings;
  show("workspace-row", (st.workspaces || []).length > 1);
  workspaceSelect.set(st.workspaces || [], currentWorkspace(settings));
  let library = { folders: [], labels: [] };
  try { library = await api("/library/folders"); } catch {}
  libraryFolders = library.folders;
  labelNames = library.labels.map((l) => l.name);
  // The default folder; a path stored before folders had ids picks the folder
  // of that path, else comes up as a new folder (the save makes it). An id the
  // library no longer lists leaves the field empty: the library root.
  const { folder, folder_path } = defaultFolder(settings);
  const found = libraryFolders.find((f) => f.id === folder);
  folderField.set(folder_path ? [folderFromText(folder_path)] : found ? [folderChip(found)] : []);
  // Prefill the options-page default labels only — the last save's labels are
  // deliberately not remembered (a save remembers just its folder).
  labelField.set([...new Set((settings.labels || []).map((s) => String(s).trim()).filter(Boolean))].map(labelChip));
}

// The app's categoryTag chip input over a ctxMenu of suggestions, for the
// folder and the labels alike: typing filters the menu, the arrow keys move
// through it, Enter commits the highlighted row or else what was typed,
// Backspace on an empty input removes the last chip. A chip, and a row, is
// `{text, value, icon, row?, chipIcon?, title?}`: `row` is the menu's
// wording when it differs from the chip's, `chipIcon` a glyph on the chip.
// `suggest(fragment, chips)` lists the rows, `fromText(fragment)` makes the
// chip typed text stands for. `single` keeps one chip, which a commit
// replaces; `separator` commits as it is typed (the labels' ","). A fragment
// left uncommitted still counts: `values()` reads the box as a save does.
function chipField({ box, input, menu, suggest, fromText, single = false, separator = "" }) {
  const placeholder = input.placeholder;
  const same = (a, b) => a.text.toLowerCase() === b.text.toLowerCase();
  let chips = [];
  let rows = [];
  let sel = -1;  // the highlighted row
  const field = {
    set(list) { chips = list; input.value = ""; render(); },
    values() {
      const typed = input.value.trim() ? fromText(input.value.trim()) : null;
      if (!typed || !typed.text) return chips;
      if (single) return [typed];
      return chips.some((c) => same(c, typed)) ? chips : [...chips, typed];
    },
  };
  const close = () => { sel = -1; menu.classList.add("hidden"); };
  function add(chip) {
    if (!chip.text) return;
    if (single) chips = [chip];
    else if (!chips.some((c) => same(c, chip))) chips = [...chips, chip];
  }
  // The labels' menu stays open for the next one; the folder is chosen.
  function commit(chip) {
    add(chip);
    input.value = "";
    sel = -1;
    render();
    if (single) close(); else update();
  }
  function render() {
    for (const el of box.querySelectorAll(".categoryTag")) el.remove();
    for (const c of chips) {
      const el = document.createElement("span");
      el.className = "categoryTag";
      el.title = c.title || c.text;
      if (c.chipIcon) el.append(icon(c.chipIcon, "", 12));
      const text = document.createElement("span"); text.className = "categoryTagText"; text.textContent = c.text;
      const x = document.createElement("button");
      x.type = "button"; x.className = "uiClose uiCloseSm"; x.tabIndex = -1; x.append(icon("x")); x.title = `Remove "${c.text}"`;
      x.addEventListener("mousedown", (e) => e.preventDefault());
      x.addEventListener("click", () => { chips = chips.filter((other) => other !== c); render(); update(); });
      el.append(text, x);
      box.insertBefore(el, input);
    }
    // One folder chip leaves nothing to prompt for; none reads "Library root".
    input.placeholder = single && chips.length ? "" : placeholder;
  }
  function highlight(i) {
    sel = i;
    [...menu.children].forEach((el, j) => el.classList.toggle("selected", j === i));
    if (i >= 0) menu.children[i].scrollIntoView({ block: "nearest" });
  }
  function update() {
    rows = document.activeElement === input ? suggest(input.value.trim(), chips) : [];
    if (!rows.length) { close(); return; }
    sel = Math.min(sel, rows.length - 1);
    menu.replaceChildren(...rows.map((r, i) => {
      const row = menuRow(r.row || r.text, r.icon, false, () => commit(r));
      row.classList.toggle("selected", i === sel);
      row.addEventListener("mouseenter", () => highlight(i));
      return row;
    }));
    placeMenu(menu);
  }
  box.addEventListener("pointerdown", (e) => {
    if (e.target === box) { e.preventDefault(); input.focus(); }
  });
  // A press on the menu (its scrollbar too) leaves the focus in the input.
  menu.addEventListener("mousedown", (e) => e.preventDefault());
  input.addEventListener("input", () => {
    if (separator && input.value.includes(separator)) {
      const parts = input.value.split(separator);
      for (const p of parts.slice(0, -1)) if (p.trim()) add(fromText(p.trim()));
      input.value = parts.at(-1).trimStart();
      render();
    }
    sel = -1;
    update();
  });
  input.addEventListener("keydown", (e) => {
    const open = !menu.classList.contains("hidden");
    if (e.key === "ArrowDown" && rows.length) {
      e.preventDefault();
      if (open) highlight(Math.min(sel + 1, rows.length - 1)); else update();
    } else if (e.key === "ArrowUp" && open) {
      e.preventDefault(); highlight(Math.max(sel - 1, -1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (open && sel >= 0) commit(rows[sel]);
      else if (input.value.trim()) commit(fromText(input.value.trim()));
    } else if (e.key === "Backspace" && !input.value && chips.length) {
      chips = chips.slice(0, -1); render(); update();
    } else if (e.key === "Escape") close();
  });
  // Focus opens the menu, and so does a click once focused (after a pick, or Escape).
  input.addEventListener("focus", update);
  input.addEventListener("click", update);
  input.addEventListener("blur", close);
  render();
  return field;
}

// Up to eight of the library's labels holding the fragment, but those chosen.
function labelSuggestions(fragment, chips) {
  const frag = fragment.toLowerCase();
  const chosen = new Set(chips.map((c) => c.value.toLowerCase()));
  return labelNames.filter((l) => !chosen.has(l.toLowerCase()) && l.toLowerCase().includes(frag)).slice(0, 8).map(labelChip);
}

const labelChip = (name) => ({ text: name, value: name, icon: "tag" });

// Every folder whose path holds the fragment, the chosen one aside, most
// recently used first (the server's order); a fragment no folder's path
// matches whole is offered as a new folder.
function folderSuggestions(fragment, chips) {
  const chosen = chips.length ? chips[0].value.folder : null;
  const rows = libraryFolders.filter((f) => f.id !== chosen && pathHolds(f.path, fragment)).map(folderChip);
  if (pathNames(fragment).length && !folderByPath(libraryFolders, fragment)) rows.push(newFolderChip(fragment));
  return rows;
}

const pathNames = (path) => path.split("/").map((name) => name.trim()).filter(Boolean);

// Whether a folder's path (its names) holds a typed one: each typed name,
// in order, within one of as many consecutive names of the path, so
// "phys/c" finds "Physics / Clocks" and "2026" any folder named with it.
function pathHolds(names, fragment) {
  const want = pathNames(fragment.toLowerCase());
  const have = names.map((name) => name.toLowerCase());
  for (let i = 0; i + want.length <= have.length; i++) {
    if (want.every((w, j) => have[i + j].includes(w))) return true;
  }
  return false;
}

// A chip's value is the filing POST /api/clip takes: a folder id, or a new
// folder's path (made by the save).
const folderChip = (f) => ({ text: f.path.join(" / "), value: { folder: f.id, folder_path: "" },
                             icon: "folder", chipIcon: "folder" });

function newFolderChip(path) {
  const names = pathNames(path);
  const text = names.join(" / ");
  return { text, value: { folder: "", folder_path: names.join("/") }, icon: "folderPlus", chipIcon: "folderPlus",
           row: `New folder “${text}”`, title: `${text} — a new folder, made when you save` };
}

// The folder a typed path names, else a new one of that path.
function folderFromText(path) {
  const found = libraryFolders.find((f) => f.id === folderByPath(libraryFolders, path));
  return found ? folderChip(found) : newFolderChip(path);
}

// As POST /api/clip names it: a folder id, or a typed new folder's path;
// neither is the library root.
function chosenFolder() {
  const [chip] = folderField.values();
  return chip ? chip.value : { folder: "", folder_path: "" };
}

function chosenLabels() {
  return labelField.values().map((c) => c.value);
}

// ---------- actions ----------

async function doSave({ candidate, force, asPage = false } = {}) {
  const c = candidate || state.candidate;
  const folder = chosenFolder();  // the worker remembers it once the save succeeds
  const labels = chosenLabels();
  $("save").disabled = true; $("save-anyway").disabled = true; $("save-page").disabled = true;
  show("result", false);
  show("progress"); $("progress-text").textContent = "starting…";
  try {
    // The worker may need to download the PDF in the browser (PDF tab, or
    // fallback when the server can't get past a paywall) — secure the host
    // permission while we still have the user's click. Best-effort: normally
    // <all_urls> is already granted and this resolves silently.
    const fetchUrls = ["pdf", "arxiv", "doi"].includes(c.kind) ? [c.pdf_url, c.source_url] : [];
    const origins = [...new Set(fetchUrls.map((u) => { try { return originPattern(new URL(u).origin); } catch { return ""; } }))]
      .filter(Boolean);
    if (origins.length) { try { await chrome.permissions.request({ origins }); } catch {} }
    const out = await send({ type: "save", tabId: tab.id, candidate: c, ...folder, labels, asPage,
                             source_url: force ? (tab && tab.url) : undefined });
    show("progress", false);
    // The paper is in the library now: the popup reads as it will when
    // reopened on this tab (as the web page, when that is what was saved).
    state = { ...state, hit: out, ...(asPage ? { candidate: c, preview: null } : {}) };
    show("save-page", state.candidate && state.candidate.kind === "maybe");
    renderHead(state);
    showExisting(state, out);
    show("existing");
    show("found", false); show("nothing", false);
    $("dot").className = "dot ok";
  } catch (err) {
    show("progress", false);
    $("result").className = "msg err";
    $("result").textContent = err.message;
    show("result");
    if (err.status === 401) { state = await send({ type: "auth-changed" }); showLogin(state); }
  } finally {
    $("save").disabled = false; $("save-anyway").disabled = false; $("save-page").disabled = false;
  }
}

// The tab's last save, step by step (worker.js keepLog), onto the clipboard
// for a bug report.
async function copyLog() {
  const k = `tab:${tab.id}`;
  const text = ((await chrome.storage.session.get(k))[k] || {}).log || "";
  const btn = $("copy-log");
  try {
    await navigator.clipboard.writeText(text);
    btn.replaceChildren(icon("check", "", 14, 2), document.createTextNode("Copied"));
  } catch (err) {
    btn.replaceChildren(document.createTextNode(`Couldn't copy: ${err.message}`));
  }
  setTimeout(() => btn.replaceChildren(icon("copy"), document.createTextNode("Copy the save log")), 1600);
}

async function doClip() {
  $("clip").disabled = true;
  show("result", false);
  try {
    const out = await send({ type: "clip-selection", tabId: tab.id, source_url: tab.url, title: tab.title });
    const r = $("result"); r.className = "msg ok"; r.innerHTML = "";
    r.appendChild(document.createTextNode("Clipped. "));
    const a = document.createElement("a"); a.href = "#"; a.textContent = "Open in Gamma";
    a.onclick = (e) => { e.preventDefault(); openPath(out.open_url); };
    r.appendChild(a); show("result");
  } catch (err) {
    $("result").className = "msg err"; $("result").textContent = err.message === "nothing selected" ? "Select some text on the page first." : err.message; show("result");
  } finally { $("clip").disabled = false; }
}

async function doConnect() {
  const origin = normalizeServer($("setup-server").value);
  const msg = $("setup-msg");
  if (!origin) { msg.textContent = "Enter a valid URL."; show("setup-msg"); return; }
  const granted = await chrome.permissions.request({ origins: [originPattern(origin)] });
  if (!granted) { msg.textContent = "Permission to talk to that server was declined."; show("setup-msg"); return; }
  await setSettings({ server: origin });
  show("setup-msg", false);
  await refresh(true);
}

async function doLogin(e) {
  if (e) e.preventDefault();
  $("login-btn").disabled = true;
  try {
    await login($("login-user").value.trim(), $("login-pass").value);
    show("login-msg", false);
    await refresh(true);
  } catch (err) {
    $("login-msg").textContent = err.status === 401 ? "Wrong username or password." : err.message;
    show("login-msg");
  } finally { $("login-btn").disabled = false; }
}

async function refresh(forceAuth = false) {
  state = await send({ type: "get-state", tabId: tab && tab.id, forceAuth });
  if (!state.configured) showSetup();
  else if (state.auth === null) showOffline(state);   // configured but unreachable
  else if (!state.auth) showLogin(state);
  else await showMain(state);
}

// ---------- wiring ----------

document.addEventListener("DOMContentLoaded", async () => {
  // ?tab=<id> targets a specific tab (used when the popup is opened as a page, e.g. in tests).
  const wanted = new URLSearchParams(location.search).get("tab");
  if (wanted) { try { tab = await chrome.tabs.get(Number(wanted)); } catch {} }
  if (!tab) [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  for (const g of document.querySelectorAll(".gearBtn")) {
    g.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><use href="#icon-gear"/></svg>';
    g.onclick = () => chrome.runtime.openOptionsPage();
  }
  for (const button of document.querySelectorAll("[data-server-switch]")) {
    button.appendChild(icon("chevronDown"));
    button.onclick = () => openServerMenu(button);
    button.addEventListener("keydown", (event) => {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        openServerMenu(button);
      }
    });
  }
  $("manage-servers").onclick = () => chrome.runtime.openOptionsPage();
  $("setup-connect").onclick = doConnect;
  $("setup-server").addEventListener("keydown", (e) => { if (e.key === "Enter") doConnect(); });
  $("login-form").addEventListener("submit", doLogin);
  $("login-btn").onclick = doLogin;
  $("offline-retry").onclick = () => refresh(true);
  workspaceSelect = workspacePicker($("workspace-btn"), $("workspace-menu"), async (value) => {
    if (!await chooseWorkspace(await getSettings(), value)) return;
    // The other library has its own folders and labels, and its own answer
    // to "already saved?" — look this tab up again there.
    await refresh();
  });
  folderField = chipField({ box: $("folder-box"), input: $("folder"), menu: $("folder-menu"), single: true,
                            suggest: folderSuggestions, fromText: folderFromText });
  labelField = chipField({ box: $("labels-box"), input: $("labels"), menu: $("label-menu"), separator: ",",
                           suggest: labelSuggestions, fromText: labelChip });
  document.addEventListener("pointerdown", (e) => {
    if (!e.target.closest("#server-menu, [data-server-switch]")) closeServerMenu();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !$("server-menu").classList.contains("hidden")) closeServerMenu(true);
    if (e.key === "Escape") closeDrawer();
  });
  $("save").onclick = () => doSave();
  $("save-anyway").onclick = () => doSave({ candidate: { kind: "none", source_url: tab.url, title: tab.title,
                                                         record: (state.candidate && state.candidate.record) || null },
                                             force: true });
  $("save-page").onclick = () => doSave({ candidate: pageCandidate(state.candidate || {}), asPage: true });
  $("save-page").append(icon("globe"), document.createTextNode("Save the web page instead"));
  $("open-existing").onclick = () => openPath(state.hit.open_url);
  $("refile").onclick = () => { show("existing", false); show("found"); };
  $("clip").onclick = doClip;
  $("clip").append(icon("scissors"), document.createTextNode("Clip selected text"));
  $("copy-log").onclick = copyLog;
  $("copy-log").append(icon("copy"), document.createTextNode("Copy the save log"));
  $("cookie-glyph").appendChild(icon("cookie", "", 16));
  $("drawer-icon").appendChild(icon("cookie"));
  $("cookie-btn").onclick = () => { $("cookie-drawer").classList.contains("hidden") ? openDrawer(state) : closeDrawer(); };
  $("cookie-close").onclick = closeDrawer;
  $("pub-auto").addEventListener("change", () => setSettings({ autoRefreshSessions: $("pub-auto").checked }));
  // Progress written by the worker while a save runs (even if this popup was reopened).
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "sync" && (changes.server || changes.servers) && !serverBusy) {
      closeServerMenu();
      closeDrawer();
      $("login-user").value = "";
      $("login-pass").value = "";
      refresh(true).catch(() => {});
    }
    if (area !== "session" || !tab) return;
    // The worker refreshed a journal session in the background while the popup is open.
    if (changes["publisher:auto"] && state && pub && pub.supported) loadPublisher(state, true);
    const ch = changes[`tab:${tab.id}`];
    if (!ch || !ch.newValue) return;
    const st = ch.newValue;
    if (st.saving) { show("progress"); $("progress-text").textContent = st.saving; }
    if (st.log) show("copy-log");
    // The registry preview lands after the popup opened — show it.
    if (st.preview && state && !state.preview && state.candidate && st.candidate
        && st.candidate.source_url === state.candidate.source_url) {
      state = { ...state, preview: st.preview };
      renderHead(state);
    }
  });
  try {
    const settings = await getSettings();
    if (settings.server) $("setup-server").value = settings.server;
    // Force a fresh session check so the footer's connection dot is truthful.
    await refresh(true);
  } catch (err) {
    showSetup();
    $("setup-msg").textContent = err.message; show("setup-msg");
  }
});
