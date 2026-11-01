// The lasso selection and the handwriting clipboard (docs/dev/handwriting.md
// "Client"): the selection, {page, items, pictures?}, which belongs to the
// lasso; its edits, each one applyInk across its groups (a move, Delete,
// the selection menu's actions), and those that take its pictures one step
// with their half in the block history (docs/dev/pictures.md "The lasso");
// copy, cut and paste through the clipboard, a ref a paste reads with the
// system clipboard written beside it; Ctrl+D, select all and the ink half
// of Transcribe. Their pure parts are selectionEdits.js. App composes it
// after the strokes.
import { useCallback, useEffect, useRef, useState } from "react";
import { guideEvents } from "../guide/events.js";
import { duplicatePicture, movePicture, picturesBySurface } from "../markup/picture.js";
import { t, T, tn } from "../shared/i18n/i18n.js";
import { makeId } from "../shared/lib/utils";
import { findBlock, makeBlockId } from "../shared/model/blockModel.js";
import { wallNow } from "../shared/model/wallClock.js";
import { cloneStrokes, removeStrokes, translateStrokes } from "./ink.js";
import * as inkStore from "./inkStore";
import {
  actionEdit, centredOn, clipOf, duplicatePlan, editPictures, inkChips, mergePlan, pasteShift, removePictures, selectAllItems,
  selectedPictures, selectedStrokes, selectionBox, selectionChanges, selectionStill as stillOf, splitPlan, transcribeGroups,
} from "./selectionEdits.js";
// Duplicate's 12 css px, as the selection's surface measures them, and the
// surface's size, which the pictures an edit carries stay on
import { duplicateOffset, surfaceSize } from "./surfaceScale.js";

// The system clipboard's copy of the handwriting clipboard, best effort
// (docs/dev/handwriting.md "Copy, cut and paste"): text/html a <div
// data-gamma-ink> whose text is the gamma/ink JSON, text/plain the JSON. A
// browser that refuses the write (no permission, no focus, plain http)
// loses nothing: the hook's inkClipRef is what a paste reads.
function writeInkClipboard(clip) {
  try {
    if (!navigator.clipboard?.write || typeof ClipboardItem === "undefined") return;
    const json = JSON.stringify(clip);
    const div = document.createElement("div");
    div.setAttribute("data-gamma-ink", "");
    div.textContent = json;
    navigator.clipboard.write([new ClipboardItem({
      "text/html": new Blob([div.outerHTML], { type: "text/html" }),
      "text/plain": new Blob([json], { type: "text/plain" }),
    })]).catch(() => {});
  } catch { /* the ref stands */ }
}

// focusedBlockId: the open page; leaving it drops the selection.
// readOnly: nothing is selected or edited. inkUi: the strip's state
// (useInkStrip.js); a tool other than the lasso drops the selection.
// blocksRef: the page's tree, read when an action runs. inkOf, applyInk:
// a group's strokes now, and the edit (useInkDocument.js). flushInk: the
// save (useInkSave.js), awaited before whole groups are transcribed.
// handleInkStrokes: a paste's strokes into a group (useInkStrokes.js).
// pickInkTool, revealInkStrip(): the strip's (the lasso armed; the strip
// shown with its row closed). inkBySurface, pasteTarget(): the page's
// groups by surface, and the surface in view (useInkSurfaces.js).
// onBoxDeselect(): the text boxes let go of theirs. showInNotes(id):
// App's. transcribe({blocks} | {chips}): the chat half of Transcribe
// (App's askToTranscribe), null in a shared page's view. stepBlocks(link,
// fn): App's tree edit fn(tree) → tree that is the block history's half of
// a step, linked to the stroke history's entry by `link`. setStatus: the
// status pill.
export function useInkSelection({ focusedBlockId, readOnly, inkUi, blocksRef, inkOf, applyInk, flushInk,
  handleInkStrokes, pickInkTool, revealInkStrip, inkBySurface, pasteTarget, onBoxDeselect, showInNotes,
  transcribe, stepBlocks, setStatus }) {
  // The lasso selection {page, items, pictures?}.
  const [inkSelection, setInkSelection] = useState(null);
  // The handwriting clipboard (docs/dev/handwriting.md "Copy, cut and
  // paste"): {type: "gamma/ink", version: 2, space, box: {x1, y1, x2, y2},
  // strokes} — the file's space, the selection's box in points and the raw
  // stroke objects. The ref is the one source a paste reads (a ClipboardItem
  // is written beside it, best effort, for other apps); `inkClip` mirrors
  // whether it holds strokes, for the command guards and the layers.
  const inkClipRef = useRef(null);
  const [inkClip, setInkClip] = useState(false);
  // The selection's commands for App and the other owners: let go of it
  // (an undo, a box selected, Play, Esc), or put back one kept aside
  // (the end of a held E or squeeze, as selectionStill leaves it).
  const clearInkSelection = useCallback(() => setInkSelection(null), []);
  const restoreInkSelection = useCallback((sel) => setInkSelection(sel), []);
  // Leaving a page drops the selection.
  useEffect(() => () => { setInkSelection(null); }, [focusedBlockId]);
  // A selection belongs to the lasso tool: switching away drops it.
  useEffect(() => { if (inkUi.tool !== "select") setInkSelection(null); }, [inkUi.tool]);
  // `items` the strokes, `pictures` the picture blocks the lasso took.
  function handleInkSelect(page, items, pictures = []) {
    if (readOnly) return;
    const any = items.length > 0 || pictures.length > 0;
    setInkSelection(any ? { page, items, ...(pictures.length ? { pictures } : {}) } : null);
    if (any) {
      revealInkStrip();
      onBoxDeselect();
      guideEvents.emit("ink.selected");
    }
  }
  // The picture blocks the selection's surface draws (picturesBySurface).
  function surfacePictures() {
    return picturesBySurface(blocksRef.current).get(inkSelection.page);
  }
  // The selection's pictures an edit takes now (selectedPictures: those its
  // surface still draws, none locked since), and the size of that surface,
  // which keeps them on it (surfaceSize). None while no layer shows it.
  function takenPictures() {
    const surface = surfaceSize(inkSelection.page);
    return { surface, ids: surface ? selectedPictures(inkSelection, surfacePictures()).map((p) => p.id) : [] };
  }
  // A step of the selection: `changes` to its strokes (applyInk) and
  // blocks(tree) → tree, its pictures' half. That half goes to the block
  // history (stepBlocks), linked to the stroke history's entry, so the two
  // undo as one step. `label` names a step of strokes alone, `both` one that
  // changed pictures too. → whether it applied (the byte budget may refuse
  // it).
  function stepSelection(changes, label, both = label, blocks = null) {
    const tree = blocksRef.current;
    const link = blocks && blocks(tree) !== tree ? { id: makeId(), page: inkSelection.page } : null;
    if (!applyInk(changes, { label: link ? both : label, link })) return false;
    if (link) stepBlocks(link.id, blocks);
    return true;
  }
  // The lasso selection edited in place: its strokes group by group
  // (edit(ink, ids) → ink) and, with `pictures` {label, each(pic, surface)
  // → pic}, its pictures, kept on its surface, the step then named
  // `pictures.label`.
  function editInkSelection(edit, label, pictures = null) {
    if (readOnly || !inkSelection) return;
    const { surface, ids } = pictures ? takenPictures() : { ids: [] };
    stepSelection(selectionChanges(inkSelection, inkOf, edit), label, pictures?.label,
      ids.length ? (tree) => editPictures(tree, ids, (pic) => pictures.each(pic, surface)) : null);
  }
  function handleInkMoveSelection(page, dx, dy) {
    editInkSelection((ink, ids) => translateStrokes(ink, ids, dx, dy), t("ink move"),
      { label: T("selection move"), each: (pic, surface) => movePicture(pic, dx, dy, surface) });
  }
  // Delete (the menu's, the strip's key): the strokes and the pictures.
  function deleteInkSelection() {
    if (readOnly || !inkSelection) return;
    const { ids } = takenPictures();
    stepSelection(selectionChanges(inkSelection, inkOf, removeStrokes), t("ink deletion"), T("selection deletion"),
      ids.length ? (tree) => removePictures(tree, ids) : null);
    setInkSelection(null);
  }
  // A selection kept aside while a held E or squeeze armed another tool,
  // as it stands now: without the strokes erased meanwhile, null when
  // nothing is left.
  function selectionStill(selection) {
    return stillOf(selection, inkOf);
  }
  // The selection menu's actions. A turn, a resize, a flip, Delete and
  // Duplicate take the selection's pictures too. The rest act on its
  // strokes alone: Copy and Cut leave the pictures on the page, and Select
  // note, New note and Merge into note keep them selected beside them.
  function handleInkAction(action, value) {
    if (readOnly || !inkSelection) return;
    // A style, a shape handle's drag, a turn or a resize, and a flip about
    // the selection box's centre edit the selection in place.
    const inPlace = actionEdit(action, value, action === "flip" ? selectionBox(inkSelection, inkOf, surfacePictures()) : null);
    if (inPlace) editInkSelection(inPlace.edit, inPlace.label, inPlace.pictures);
    else if (action === "delete") deleteInkSelection();
    else if (action === "select-note") {
      handleInkSelect(inkSelection.page, inkSelection.items.map((item) => ({
        id: item.id, ids: (inkOf(item.id)?.strokes || []).map((s) => s.id),
      })), inkSelection.pictures);
    } else if (action === "show-note") {
      showInNotes(inkSelection.items[0]?.id ?? inkSelection.pictures[0]);
      setInkSelection(null);
    } else if (action === "duplicate") {
      const plan = duplicatePlan(inkSelection, inkOf, value);
      if (plan.refused) {
        setStatus(plan.refused);
        return;
      }
      // copies of the pictures too, as far off as the strokes', each just
      // over its original (duplicatePicture)
      const { surface, ids } = takenPictures();
      const copies = ids.map(() => makeBlockId()), t0 = wallNow();
      const place = (pic) => movePicture(pic, value.dx, value.dy, surface);
      // the copies become the selection once they are on the page (the
      // byte budget may refuse them, leaving the selection as it was)
      if (stepSelection(plan.changes, T("ink duplication"), T("selection duplication"),
        ids.length ? (tree) => ids.reduce((out, id, i) => duplicatePicture(out, id, copies[i], place, t0), tree) : null)) {
        handleInkSelect(inkSelection.page, plan.items, copies);
      }
    } else if (action === "copy") copyInkSelection(false);
    else if (action === "cut") copyInkSelection(true);
    else if (action === "transcribe") transcribeInkSelection();
    else if (action === "split") splitInkSelection();
    else if (action === "merge") mergeInkSelection();
  }
  // The selected strokes with their box and space, null with none.
  function selectedInk() {
    return selectedStrokes(inkSelection, inkOf);
  }
  // Copy (Ctrl+C, the menu) keeps the selection on the handwriting
  // clipboard; cut (Ctrl+X) deletes it as well, the entry "ink cut". The
  // pill offers Paste, the way to paste without a keyboard.
  function copyInkSelection(cut) {
    if (readOnly) return false;
    const sel = selectedInk();
    if (!sel) return false;
    const clip = clipOf(sel);
    inkClipRef.current = clip;
    setInkClip(true);
    writeInkClipboard(clip);
    const n = sel.strokes.length;
    if (cut) {
      editInkSelection(removeStrokes, T("ink cut"));
      setInkSelection(null);
    }
    setStatus(cut ? tn("Cut {n} stroke.", "Cut {n} strokes.", n) : tn("Copied {n} stroke.", "Copied {n} strokes.", n),
      { label: t("Paste"), run: () => pasteAtCentre() });
    return true;
  }
  // The clipboard's strokes onto `page` ({width, height} in points): fresh
  // copies with the copied box's top-left at `at` and the box kept inside
  // the surface (one too small for it keeps the top-left corner on), at
  // the same point size whatever the surface. They become the selection,
  // the lasso armed.
  async function pasteInk(page, at, size) {
    const clip = inkClipRef.current;
    if (!clip?.strokes?.length || readOnly || !size) return;
    const { dx, dy } = pasteShift(clip, at, size);
    const landed = await handleInkStrokes(page, cloneStrokes(clip.strokes, dx, dy), size);
    if (!landed) return;
    pickInkTool("select");
    handleInkSelect(page, [{ id: landed.id, ids: landed.ids }]);
  }
  // Ctrl+V, the pill's Paste and More → Paste handwriting: the copied box
  // centred on the surface in view.
  function pasteAtCentre() {
    const target = pasteTarget(), clip = inkClipRef.current;
    if (!target || !clip) return;
    const { page, size } = target;
    pasteInk(page, centredOn(clip, size), size);
  }
  // Ctrl+D: the menu's Duplicate, the copies the same 12 css px away, in
  // the points of the selection's own surface as its layer measures them.
  function duplicateInkSelection() {
    if (inkSelection) handleInkAction("duplicate", duplicateOffset(inkSelection.page));
  }
  // Ctrl+A, More → Select all: every stroke of every group on the
  // selection's surface, else the one in view, the lasso armed.
  function selectAllInk() {
    const page = inkSelection?.page ?? pasteTarget()?.page;
    if (page == null) return false;
    const items = selectAllItems(inkBySurface.get(page), inkOf);
    if (!items.length) return false;
    pickInkTool("select");
    handleInkSelect(page, items);
    return true;
  }
  // Transcribe (the selection menu's second row). A selection of whole
  // groups asks for their text as "Transcribe with AI" does: the page's
  // drafts are saved first (the server reads the stored files) and each
  // group's block rides as a chip, the oldest attached block making room.
  // Part of a group — or a group whose save has not landed — sends the
  // selected strokes themselves, as the drafts hold them: one chip per
  // group, the server's ink_selections (gamma/ai_context.py), within its
  // caps; past them nothing is sent. The chat half is `transcribe`'s
  // (App's askToTranscribe), which a shared page's view does not pass.
  async function transcribeInkSelection() {
    const sel = inkSelection;
    if (!sel || !focusedBlockId) return;
    if (!transcribe) { setStatus(t("Transcribing needs the AI chat, which a shared page cannot use.")); return; }
    const { groups, whole } = transcribeGroups(sel, inkOf);
    if (whole) {
      await flushInk();
      const unsaved = new Set(inkStore.dirtyDrafts().map((d) => d.id));
      const blocks = groups.map(({ id }) => findBlock(blocksRef.current, id));
      if (blocks.every(Boolean) && !groups.some(({ id }) => unsaved.has(id))) {
        transcribe({ blocks });
        return;
      }
    }
    const chips = inkChips(groups, focusedBlockId);
    if (!chips.length) return;
    transcribe({ chips });
  }
  // New note from selection: the selected strokes leave their groups for a
  // new ink block on the same surface, ids kept, as one entry across the
  // groups and the new block (applyInk inserts it; undo empties it again,
  // and flushInk deletes the empty block). A selection that already is a
  // whole note of its own is left as it is. The new note becomes the
  // selection once applyInk applied it: the byte budget may refuse it, as
  // it may a merge, leaving the selection as it was.
  function splitInkSelection() {
    const plan = splitPlan(inkSelection, inkOf, makeId);
    if (!plan) return;
    if (plan.refused) { setStatus(plan.refused); return; }
    if (applyInk(plan.changes, { label: T("ink split") })) handleInkSelect(inkSelection.page, plan.items, inkSelection.pictures);
  }
  // Merge into note: the selection's whole group appended to the surface's
  // previous group (the nearest before it in page order, else the first
  // after it) and this one emptied, one entry; a group whose block holds a
  // caption or notes is refused, since its block would stay behind empty.
  function mergeInkSelection() {
    const plan = mergePlan(inkSelection, inkOf, blocksRef.current, inkBySurface);
    if (!plan) return;
    if (plan.refused) { setStatus(plan.refused); return; }
    if (applyInk(plan.changes, { label: T("ink merge") })) handleInkSelect(inkSelection.page, plan.items, inkSelection.pictures);
  }
  return { inkSelection, inkClip, clearInkSelection, restoreInkSelection, handleInkSelect, handleInkMoveSelection,
    deleteInkSelection, selectionStill, handleInkAction, copyInkSelection, pasteInk, pasteAtCentre,
    duplicateInkSelection, selectAllInk };
}
