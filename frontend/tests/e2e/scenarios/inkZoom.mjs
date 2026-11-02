// The zoom window and transcribing a selection (docs/dev/handwriting.md
// "Zoom window" and "Transcribe a selection"): More → Zoom window docks the
// panel under the pages with the window's box on the page; a stroke written
// in the panel, drawn live on a canvas of what the panel shows, is saved in
// the page's group at page coordinates (the window's offset and
// magnification applied) and is there after a reload; Previous, Next and
// New line move the window, a stroke reaching into its right fifth moves it
// on along the line, the box's band drags it (the panel following at the
// release) and a click on a blank spot of the page centres it there; Z
// closes it. A drag of the panel's grip shows its height as it moves (the
// scroller giving up as much) and applies it once, at the release; the keys
// apply each step at once. The selection menu's Convert → Transcribe in
// the chat sends a selection that takes part of a group as that group's
// strokes (`ink_selections`), and a whole group as its block's chip, as
// Transcribe with AI does (the AI stubbed: no provider is called). Last, a
// picture another client places on the page shows in the panel under its
// handwriting, drawn as the page draws it (dimmed, never inverted, on a
// dark page), a highlighter multiplying into it; a press there is the
// ink's, a move of the picture shows at once, and under a recording's
// player it is shaded until its moment, as on the page. A
// second picture after it in the notes but under it by its `z` stacks
// under it in the panel too. The window's rules themselves are
// tests/zoomWindow.test.mjs; the server's side of a selection is
// backend/tests/test_ai_tools_ink.py.
import { assertNear as near, fakeAiModels, live } from "../harness.mjs";
import { waitForPdf } from "./pdf.mjs";
import { armHighlighter, highlighted, png, sameColour, screenPixels } from "./pictures.mjs";
import { fakeMedia } from "./recording.mjs";
import { decodeStroke, encodeStroke, newInk, strokeBounds } from "../../../src/ink/ink.js";
import { advance, moveTo, moveWindow, newLine, nextLine, prevLine } from "../../../src/ink/zoomWindow.js";
import { normalizePicture, turnedBox } from "../../../src/markup/picture.js";

const PAGE = { width: 612, height: 792 };

export async function inkZoomScenarios({ server, browser, alice, makePdf, step, until, assert, assertEq, assertNoProblems, openPage }) {
  const account = alice;
  let ctx, page, pageId;
  // Every stroke the page's groups hold on the server.
  const savedStrokes = async () => {
    const out = [];
    for (const b of (await account.api(`/api/blocks/${pageId}/subtree`)).block.children || []) {
      if (!b.properties?.ink_url?.endsWith(".ink")) continue;
      for (const stroke of (await account.api(b.properties.ink_url)).strokes) out.push({ block: b.id, stroke });
    }
    return out;
  };
  const panel = () => page.getByRole("region", { name: "Zoom window", exact: true });
  const bar = () => page.getByRole("toolbar", { name: "Zoom window controls", exact: true });
  // The window as the page's box shows it, in page points; the window state
  // the zoomWindow.js rules take (the line height 24 and where the line began
  // are the test's own record of what it did).
  const box = () => page.locator(".pdfViewer .inkZoomRect").evaluate((el) =>
    ({ x: Number(el.dataset.x), y: Number(el.dataset.y), w: Number(el.dataset.w), h: Number(el.dataset.h) }));
  let x0 = 0;
  const state = async () => ({ page: 1, ...(await box()), mag: Number(await panel().getAttribute("data-mag")), line: 24, x0 });
  const sameBox = (a, b, what) => { for (const k of ["x", "y", "w", "h"]) near(a[k], b[k], 0.06, `${what} (${k})`); };
  // A page point where the panel shows it, and where the page itself does.
  const inPanel = async (x, y) => {
    const z = await state(), v = await page.locator(".inkZoomView").boundingBox();
    return { x: v.x + (x - z.x) * z.mag, y: v.y + (y - z.y) * z.mag };
  };
  const onPage = (x, y) => page.locator('.pdfViewer [data-page="1"]').evaluate((el, [x, y]) => {
    const r = el.getBoundingClientRect(), k = r.width / 612;
    return { x: r.left + x * k, y: r.top + y * k };
  }, [x, y]);
  const draw = async (from, to) => {
    await page.mouse.move(from.x, from.y);
    await page.mouse.down();
    await page.mouse.move(to.x, to.y, { steps: 12 });
    await page.mouse.up();
  };
  // The window once the panel has measured its view (w × mag and h × mag
  // its size): where its line begins is where it opened.
  const settled = async () => {
    await panel().waitFor();
    const view = await page.locator(".inkZoomView").boundingBox();
    const z = await until(async () => {
      const s = await state();
      return Math.abs(s.w * s.mag - view.width) <= 1 && Math.abs(s.h * s.mag - view.height) <= 1 ? s : null;
    }, { what: "w × mag and h × mag are the view's size" });
    x0 = z.x;
    return { ...z, x0 };
  };
  const openWindow = async () => {
    await page.locator(".pdfInkBar button[aria-label='More handwriting tools']").click();
    await page.locator(".inkStripMenu").getByRole("menuitemcheckbox", { name: "Zoom window" }).click();
    return settled();
  };

  await step("ink zoom: More → Zoom window docks the panel under the pages at 3×, its box on the page and the page's pixels behind it", async () => {
    const up = await account.upload("/api/uploads", makePdf([["The zoom window writes large", "on this page"]]), "zoom.pdf", "application/pdf");
    const created = await account.api(`/api/blocks/by-doc/${up.doc_id}`, { method: "POST", body: { default_title: "Zoom paper", source_url: up.source_url } });
    pageId = created.id;
    ctx = await account.context(browser);
    page = await openPage(ctx, `${server.base}/?page=${pageId}&ws=${account.ws}`);
    await waitForPdf(page, 1);
    await page.click("button[aria-label='Handwriting tools']");
    await page.waitForSelector(".pdfInkBar");
    assertEq(await page.locator(".pdfInkBar button[aria-label='Zoom window']").count(), 0, "no Zoom window button while it is closed");
    const z = await openWindow();
    assertEq(await panel().getAttribute("data-mag"), "3", "it opens at 3×");
    assertEq(await panel().getAttribute("data-page"), "1");
    assertEq(await panel().getAttribute("data-guide"), "ink.zoom");
    assertEq(await page.locator(".pdfInkBar button[aria-label='Zoom window']").getAttribute("aria-pressed"), "true", "the pressed toggle in the strip");
    assertEq(await page.locator(live(".inkStripMenu")).count(), 0, "the menu closed");
    // docked under the scroller, which gives up its 200 px
    const scroller = await page.locator(".pdfViewer").boundingBox(), docked = await panel().boundingBox();
    near(docked.height, 200, 1, "the panel's height");
    near(scroller.y + scroller.height, docked.y, 1.5, "the panel starts where the scroller ends");
    // the window fills the view at 3× (openWindow waited for that) and lies on the page in view
    assert(z.x >= 0 && z.y >= 0 && z.x + z.w <= 612.01 && z.y + z.h <= 792.01, `the window on its page: ${JSON.stringify(z)}`);
    for (const name of ["Previous line", "Next line", "New line", "Smaller line height", "Larger line height", "Close the zoom window"]) {
      assertEq(await bar().getByRole("button", { name, exact: true }).count(), 1, `the bar's ${name}`);
    }
    assertEq(JSON.stringify(await bar().getByRole("group", { name: "Magnification" }).locator("button").allTextContents()), JSON.stringify(["2×", "3×", "4×"]));
    assertEq(await bar().locator(".inkZoomReadout").textContent(), "24 pt", "the line height to start");
    await until(() => page.locator(".inkZoomRaster").evaluateAll((els) => els.some((c) => c.style.display === "block" && c.width > 0)),
      { what: "the page's pixels drawn in the panel" });
    assertNoProblems(page);
  });

  await step("ink zoom: a drag of the grip shows the height as it moves, the scroller following and the page's pixels filling the view, and applies it once at the release (a key waits for it); a reload keeps it; ↑ / ↓ apply at once", async () => {
    const grip = () => page.getByRole("separator", { name: "Zoom window height", exact: true });
    const pref = () => page.evaluate(() => localStorage.getItem("gamma-ink-zoom-height"));
    // the panel's height, and whether the scroller ends where the panel starts
    const docked = async () => {
      const scroller = await page.locator(".pdfViewer").boundingBox(), shown = await panel().boundingBox();
      return { height: shown.height, gap: shown.y - (scroller.y + scroller.height) };
    };
    // the css px of the page in the view that no drawn raster stands under
    const unpainted = () => page.locator(".inkZoomView").evaluate((v) => {
      const view = v.getBoundingClientRect(), pg = v.querySelector(".inkZoomPage").getBoundingClientRect();
      const top = Math.max(view.top, pg.top), bottom = Math.min(view.bottom, pg.bottom);
      const drawn = [...v.querySelectorAll(".inkZoomRaster")].find((c) => c.style.display === "block" && c.width > 0);
      if (!drawn) return bottom - top;
      const r = drawn.getBoundingClientRect();
      return Math.max(0, r.top - top) + Math.max(0, bottom - r.bottom);
    });
    const before = await state();
    assertEq(await pref(), "200");
    const g = await grip().boundingBox();
    const dragTo = (dy) => page.mouse.move(g.x + g.width / 2, g.y + g.height / 2 - dy, { steps: 6 });
    await grip().focus();
    await page.mouse.move(g.x + g.width / 2, g.y + g.height / 2);
    await page.mouse.down();
    await dragTo(60);
    // while it moves: the panel and the scroller show the height, the viewer's controls above it ...
    await until(async () => Math.abs((await docked()).height - 260) <= 1, { what: "the panel follows the grip" });
    near((await docked()).gap, 0, 1.5, "the scroller ends where the panel starts");
    assertEq(await grip().getAttribute("aria-valuenow"), "260");
    assertEq(await page.locator(".viewerWrap").evaluate((el) => el.style.getPropertyValue("--ink-zoom-h")), "260px", "the viewer's controls stand above it");
    // ... while the preference, and the window every page's layers read, wait for the release
    assertEq(await pref(), "200", "nothing written during the drag");
    sameBox(await box(), before, "the window waits for the release");
    // a key during the drag waits for it too: the drag owns the height until it ends
    await page.keyboard.press("ArrowUp");
    await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
    assertEq(await pref(), "200", "↑ during the drag applies nothing");
    near((await docked()).height, 260, 1, "the panel keeps the dragged height");
    near((await docked()).gap, 0, 1.5, "and the scroller still ends where it starts");
    // grown past the margin the window's pixels were drawn with, the view fills with the page while held
    await dragTo(120);
    await until(async () => Math.abs((await docked()).height - 320) <= 1, { what: "the panel at its tallest" });
    await until(async () => (await unpainted()) <= 1, { what: "the page's pixels under the whole view while the grip is held" });
    await dragTo(60);
    await until(async () => Math.abs((await docked()).height - 260) <= 1, { what: "back to 260" });
    await page.mouse.up();
    await until(async () => (await pref()) === "260", { what: "the height applied at the release" });
    await until(async () => {
      const s = await state(), view = await page.locator(".inkZoomView").boundingBox();
      return Math.abs(s.h * s.mag - view.height) <= 1;
    }, { what: "the window as tall as the view, once" });
    near((await docked()).height, 260, 1, "the panel keeps the height");
    near((await docked()).gap, 0, 1.5, "and the scroller still ends where it starts");
    // the browser keeps it: the window opens at that height after a reload
    await page.reload();
    await waitForPdf(page, 1);
    await page.click("button[aria-label='Handwriting tools']");
    await page.waitForSelector(".pdfInkBar");
    await openWindow();
    near((await docked()).height, 260, 1, "the height after a reload");
    near((await docked()).gap, 0, 1.5);
    // ↑ / ↓ step it by 10 px, each applied at once
    await grip().focus();
    await page.keyboard.press("ArrowDown");
    await until(async () => (await pref()) === "250", { what: "↓ applied at once" });
    near((await docked()).height, 250, 1);
    for (let i = 0; i < 5; i++) await page.keyboard.press("ArrowDown");
    await until(async () => (await pref()) === "200", { what: "back to 200" });
    await until(async () => Math.abs((await docked()).height - 200) <= 1, { what: "the panel at 200 px" });
    await settled();
    assertNoProblems(page);
  });

  let first;
  await step("ink zoom: a stroke written in the panel is saved in the page's group at page coordinates, and is there after a reload", async () => {
    const z = await state();
    const y = z.y + z.h / 2, from = { x: z.x + 0.15 * z.w, y }, to = { x: z.x + 0.45 * z.w, y };
    const a = await inPanel(from.x, from.y), b = await inPanel(to.x, to.y);
    await page.mouse.move(a.x, a.y);
    await page.mouse.down();
    await page.mouse.move((a.x + b.x) / 2, a.y, { steps: 6 });
    // the stroke in progress is drawn on a canvas of what the panel shows, at the device's ratio — not one
    // of the whole page at 3×, which the canvas caps would hold to a ratio near 1 on an iPad
    const live = await panel().locator(".inkCanvas").evaluate((c) => {
      const r = c.getBoundingClientRect(), v = c.closest(".inkZoomView").getBoundingClientRect();
      return { display: c.style.display, w: c.width, cw: r.width, ch: r.height, vw: v.width, vh: v.height, dpr: devicePixelRatio,
        painted: c.getContext("2d").getImageData(0, 0, c.width, c.height).data.some((x, i) => i % 4 === 3 && x > 0) };
    });
    assert(live.display === "block" && live.painted, `the live line drawn in the panel (${JSON.stringify(live)})`);
    assert(live.cw <= live.vw + 2 && live.ch <= live.vh + 2, `the live canvas covers the view, not the page (${JSON.stringify(live)})`);
    near(live.w / live.cw, live.dpr, 0.02, "the live canvas at the device's pixel ratio");
    await page.mouse.move(b.x, b.y, { steps: 6 });
    await page.mouse.up();
    const saved = await until(async () => { const all = await savedStrokes(); return all.length === 1 ? all[0] : null; }, { what: "one saved stroke" });
    const samples = decodeStroke(saved.stroke);
    // a css px in the panel is a third of a point: the samples land within a point of where the pointer went
    near(samples[0].x, from.x, 1, "the stroke starts where the pointer went down, in page points");
    near(samples.at(-1).x, to.x, 1, "and ends where it lifted");
    for (const s of samples) near(s.y, y, 1, "the line's height on the page");
    assertEq(await page.locator('.pdfViewer [data-page="1"] .inkLayer path').count(), 1, "the page draws it");
    assertEq(await panel().locator(".inkLayer path").count(), 1, "and the panel draws the same stroke");
    sameBox(await box(), z, "a stroke short of the right fifth leaves the window where it is");
    // the panel's layer has no chrome: no selection menu, handles or window box of its own
    assertEq(await panel().locator(".inkZoomRect, .inkTransformHandle").count(), 0);
    first = saved;
    await page.reload();
    await waitForPdf(page, 1);
    await until(async () => (await page.locator('.pdfViewer [data-page="1"] .inkLayer path').count()) === 1, { what: "the stroke after the reload" });
    assertEq(await page.locator(".inkZoomPanel").count(), 0, "the window is session state: closed after a reload");
    const after = await savedStrokes();
    assertEq(JSON.stringify(after.map((s) => s.stroke.pts)), JSON.stringify([first.stroke.pts]), "the stored stroke unchanged");
    assertNoProblems(page);
  });

  await step("ink zoom: Previous, Next and New line move the window; a stroke in its right fifth moves it on along the line; the band drags it; a click centres it; Z closes it", async () => {
    await page.click("button[aria-label='Handwriting tools']");
    await page.waitForSelector(".pdfInkBar");
    await page.keyboard.press("z");
    const start = await settled();
    await bar().getByRole("button", { name: "Next line", exact: true }).click();
    await until(async () => Math.abs((await box()).y - nextLine(start, PAGE).y) < 0.06, { what: "one line down" });
    sameBox(await box(), nextLine(start, PAGE), "Next line keeps x");
    await bar().getByRole("button", { name: "Previous line", exact: true }).click();
    await until(async () => Math.abs((await box()).y - start.y) < 0.06, { what: "one line up again" });
    // a stroke reaching into the right fifth: on by three fifths of the width (or to the next line at the page's edge)
    const z = await state(), y = z.y + z.h / 2;
    await draw(await inPanel(z.x + 0.7 * z.w, y), await inPanel(z.x + 0.92 * z.w, y));
    const second = await until(async () => (await savedStrokes()).find((s) => s.stroke.id !== first.stroke.id) || null, { what: "the second stroke" });
    const want = advance(z, strokeBounds(second.stroke), PAGE);
    assert(want !== z, "the stroke reached past 80 % of the window");
    await until(async () => Math.abs((await box()).x - want.x) < 0.06 && Math.abs((await box()).y - want.y) < 0.06, { what: "the window moved on" });
    near(decodeStroke(second.stroke)[0].x, z.x + 0.7 * z.w, 1, "the second stroke where it was written");
    // New line: back to where the line began, one line down (Enter does the same with nothing focused)
    const moved = await state();
    await page.evaluate(() => document.activeElement?.blur());
    await page.keyboard.press("Enter");
    await until(async () => Math.abs((await box()).y - newLine(moved, PAGE).y) < 0.06, { what: "Enter: a new line" });
    sameBox(await box(), newLine(moved, PAGE), "New line goes back to the line's start");
    // the line height steps by 2 pt
    await bar().getByRole("button", { name: "Larger line height", exact: true }).click();
    assertEq(await bar().locator(".inkZoomReadout").textContent(), "26 pt");
    await bar().getByRole("button", { name: "Smaller line height", exact: true }).click();
    assertEq(await bar().locator(".inkZoomReadout").textContent(), "24 pt");
    // 2× doubles the window's size around the line's middle
    const before = await state();
    await bar().getByRole("button", { name: "2×", exact: true }).click();
    await until(async () => (await panel().getAttribute("data-mag")) === "2", { what: "2×" });
    near((await box()).w, before.w * 1.5, 0.06, "the window at 2× is half as wide again as at 3×");
    await bar().getByRole("button", { name: "3×", exact: true }).click();
    await until(async () => (await panel().getAttribute("data-mag")) === "3", { what: "3× again" });
    // the page's band drags the window; its line starts where it lands
    const held = await state(), band = await page.locator(".pdfViewer .inkZoomBand").boundingBox();
    const k = (await page.locator('.pdfViewer [data-page="1"]').boundingBox()).width / 612;
    const dragged = moveWindow(held, { x: held.x + 30 / k, y: held.y + 60 / k }, PAGE);
    const shownBefore = await page.locator(".inkZoomPage").evaluate((el) => el.style.transform);
    await page.mouse.move(band.x + band.width / 2, band.y + band.height / 2);
    await page.mouse.down();
    await page.mouse.move(band.x + band.width / 2 + 30, band.y + band.height / 2 + 60, { steps: 6 });
    // the box previews the drag; the window itself, which every page's layers read, moves once, at the release
    await until(async () => Math.abs((await box()).y - dragged.y) < 0.6, { what: "the box follows the band" });
    assertEq(await page.locator(".inkZoomPage").evaluate((el) => el.style.transform), shownBefore, "the panel waits for the release");
    await page.mouse.up();
    await until(async () => (await page.locator(".inkZoomPage").evaluate((el) => el.style.transform)) !== shownBefore,
      { what: "the panel moved at the release" });
    await until(async () => Math.abs((await box()).y - dragged.y) < 0.6, { what: "the band dragged the window" });
    near((await box()).x, dragged.x, 0.6, "the window followed the band");
    assertEq(await page.locator(".pdfViewer [data-page='1'] .inkLayer path").count(), 2, "the drag drew nothing");
    // with the hand, a click on a blank spot centres the window there (above the box: clear of its band and corner)
    await page.keyboard.press("v");
    const spot = { x: 500, y: Math.round((await box()).y - 60) };
    const at = await onPage(spot.x, spot.y);
    await page.mouse.click(at.x, at.y);
    const centred = moveTo(await state(), 1, spot, PAGE);
    await until(async () => { const b = await box(); return Math.abs(b.x - centred.x) < 0.6 && Math.abs(b.y - centred.y) < 0.6; },
      { what: "the window centred on the click" });
    await page.keyboard.press("z");
    await until(async () => !(await page.locator(".inkZoomPanel").count()), { what: "Z closes the window" });
    assertEq(await page.locator(".pdfViewer .inkZoomRect").count(), 0, "its box goes with it");
    assertEq(await page.locator(".pdfInkBar button[aria-label='Zoom window']").count(), 0, "and the strip's toggle");
    // Escape takes it away one layer at a time: the window before the strip
    await page.keyboard.press("z");
    await panel().waitFor();
    await page.keyboard.press("Escape");
    await until(async () => !(await page.locator(".inkZoomPanel").count()), { what: "Escape closes the window" });
    assertEq(await page.locator(".pdfInkBar").count(), 1, "the strip stays");
    assertNoProblems(page);
    await ctx.close();
  });

  await step("ink zoom: Transcribe on part of a group sends those strokes as ink_selections; on a whole group, its block as a chip", async () => {
    // right of what the zoom steps wrote, two lines apart
    const strokes = [["a", 220], ["b", 300]].map(([id, y]) => encodeStroke({ id, color: "#1f1f1f", size: 2, ch: "xyt", t0: 1000,
      samples: Array.from({ length: 11 }, (_, i) => ({ x: 400 + i * 10, y, t: i * 8 })) }));
    const file = await account.api("/api/upload-ink", { method: "POST", body: { ...newInk(1, 612, 792), strokes } });
    const group = await account.api("/api/blocks", { method: "POST", body: { parent_id: pageId, content: "",
      properties: { ink_url: file.url, pdf_position: file.pdf_position, ink_strokes: 2 } } });
    const tctx = await account.context(browser);
    try {
      await fakeAiModels(tctx);
      await tctx.addInitScript(() => localStorage.setItem("gamma-ai-login-check", "off"));
      const sent = [];
      await tctx.route("**/api/ai/chat", async (route) => {
        sent.push(route.request().postDataJSON());
        await route.fulfill({ contentType: "application/x-ndjson", body: '{"delta":"It says: hello."}\n' });
      });
      page = await openPage(tctx, `${server.base}/?page=${pageId}&ws=${account.ws}`);
      await waitForPdf(page, 1);
      await until(async () => (await page.locator('.pdfViewer [data-page="1"] .inkLayer path').count()) === 4, { what: "the page's four strokes" });
      // a click on stroke "a" with nothing armed selects it alone: part of its group
      const at = await onPage(450, 220);
      await page.mouse.click(at.x, at.y);
      const menu = page.getByRole("toolbar", { name: "Edit handwriting", exact: true });
      await menu.waitFor();
      await menu.getByRole("button", { name: "More", exact: true }).click();
      const row = menu.locator(".inkEditMore .ctlBtn");
      assertEq(await row.count(), 7, "seven buttons in the second row");
      assertEq(await menu.getByRole("button", { name: "New note", exact: true }).count(), 1, "part of a group: New note, not Merge");
      // the row's last button, Convert, opens a menu whose first row is Transcribe in the chat
      const convert = menu.getByRole("button", { name: "Convert", exact: true });
      assertEq(await convert.getAttribute("title"), "Transcribe, redraw or convert the selected handwriting");
      assertEq(await convert.getAttribute("aria-haspopup"), "menu");
      const transcribe = () => page.getByRole("menu", { name: "Convert" }).getByRole("menuitem", { name: "Transcribe in the chat", exact: true });
      await convert.click();
      assertEq(await transcribe().getAttribute("title"), "Transcribe with AI: the selected handwriting as text in the chat");
      await transcribe().click();
      await until(async () => !(await page.locator(".inkConvertMenu").count()), { what: "the menu closed by its row" });
      await until(() => sent.length === 1, { what: "one chat request" });
      assertEq(sent[0].prompt, "Transcribe the selected handwriting.", "the request's text");
      assertEq(sent[0].ink_selections.length, 1, "one group's selection");
      const [sel] = sent[0].ink_selections;
      assertEq(JSON.stringify([sel.page_id, sel.block_id, sel.strokes.map((s) => s.id)]), JSON.stringify([pageId, group.id, ["a"]]),
        "the selected stroke alone, with its group's block and page");
      assertEq(JSON.stringify([sel.space.kind, sel.space.page]), JSON.stringify(["pdf-page", 1]), "in its group's space");
      assert(!sent[0].context_blocks.includes(group.id), "no block chip for a part of a group");
      await page.locator(".chatBubble.ai", { hasText: "It says: hello." }).waitFor();
      assertEq(await page.locator(".chatSelChip.isInk").count(), 0, "the chip went with the message");
      // Select note takes the whole group: Transcribe then attaches its block, as Transcribe with AI does
      await menu.getByRole("button", { name: "Select note", exact: true }).click();
      await until(async () => Number(await page.locator(".pdfViewer .inkSelRect").getAttribute("height")) > 60, { what: "the whole group selected" });
      await convert.click();
      await transcribe().click();
      await until(() => sent.length === 2, { what: "a second chat request" });
      assertEq(sent[1].prompt, "Transcribe this handwriting into its caption.");
      assert(sent[1].context_blocks.includes(group.id), "the group's block rides as a chip");
      assertEq(sent[1].ink_selections.length, 0, "and no strokes inline");
      assertNoProblems(page);
    } finally { await tctx.close(); }
  });

  await step("ink zoom: a picture shows in the panel under the handwriting, placed, turned, flipped and cropped as on the page, dimmed and not inverted on a dark page, a highlighter multiplying into it; a press there is the ink's; a move shows at once; a recording's player shades it until its moment; a second picture sent under it by its z stacks under it there too", async () => {
    const up = await account.upload("/api/uploads", makePdf([["Pictures in the zoom window"]]), "zoom-pictures.pdf", "application/pdf");
    const created = await account.api(`/api/blocks/by-doc/${up.doc_id}`, { method: "POST", body: { default_title: "Zoom pictures", source_url: up.source_url } });
    pageId = created.id;
    const { url } = await account.upload("/api/upload-image", png(80, 40), "zoomed.png", "image/png");
    // a recording on the page a minute long, begun two minutes ago (its one segment a WebM header: the fake Audio plays it)
    const audio = await account.upload("/api/upload-file", Buffer.from([0x1a, 0x45, 0xdf, 0xa3, 0x84, 0x42, 0x86, 0x81, 0x01]), "zoom.webm", "audio/webm");
    const started = Date.now() - 120_000;
    await account.api("/api/blocks", { method: "POST", body: { parent_id: pageId, content: "Zoom audio",
      properties: { t0: started, recording: { started, state: "done", dur: 60_000,
        segments: [{ url: audio.url, started, dur: 60_000, bytes: 9, mime: "audio/webm" }] } } } });
    const pctx = await account.context(browser);
    await pctx.addInitScript(fakeMedia);
    try {
      page = await openPage(pctx, `${server.base}/?page=${pageId}&ws=${account.ws}`);
      await waitForPdf(page, 1);
      await page.click("button[aria-label='Handwriting tools']");
      await page.waitForSelector(".pdfInkBar");
      const z = await openWindow();
      // another client places it in the window's middle: turned, flipped, and cropped to the image's right three quarters
      const cx = z.x + z.w / 2, cy = z.y + z.h / 2;
      const pic = normalizePicture({ url, x: cx - 30, y: cy - 12, w: 60, h: 24, angle: 20, flip_h: true, crop: { x: 0.25, y: 0, w: 0.75, h: 1 } });
      const setPicture = (op) => account.api(`/api/pages/${pageId}/ops`, { method: "POST", body: { client: "e2e", ops: [op] } });
      await setPicture({ op: "insert", id: "zoomPic", parent: pageId, content: "", props: { picture: pic, pdf_page: 1 } });
      const zoomed = panel().locator(".pagePicture"), pagePic = page.locator('.pdfViewer [data-picture-id="zoomPic"]');
      await until(async () => (await zoomed.count()) === 1 && zoomed.locator("img").evaluate((el) => el.complete && el.naturalWidth > 0),
        { what: "the picture in the panel, its image loaded" });
      // the page's drawing (PictureView): the frame's place and turn, the flip, the crop
      const drawing = (frame) => frame.evaluate((el) => [el, el.querySelector(".pagePictureFlip"), el.querySelector("img")].map((e) => e.getAttribute("style")));
      assertEq(JSON.stringify(await drawing(zoomed)), JSON.stringify(await drawing(pagePic)), "drawn as the page draws it");
      // where the window shows its place: the turned frame at `mag` css px a point
      const [x0, y0, x1, y1] = turnedBox(pic), corner = await inPanel(x0, y0), shown = await zoomed.boundingBox();
      near(shown.x, corner.x, 1.5, "the turned frame's left in the panel");
      near(shown.y, corner.y, 1.5, "its top");
      near(shown.width, (x1 - x0) * z.mag, 1.5, "its width at the magnification");
      near(shown.height, (y1 - y0) * z.mag, 1.5, "its height");
      // a press over it is the panel's ink's: the picture takes none
      const a = (pic.angle * Math.PI) / 180;
      const along = (f) => inPanel(cx + f * pic.w * Math.cos(a), cy + f * pic.w * Math.sin(a)); // f of the frame's width from its centre
      const left = await along(-0.25), right = await along(0.25);
      assertEq(await page.evaluate(({ x, y }) => !!document.elementFromPoint(x, y)?.closest(".pagePicture"), right), false,
        "a press over it lands on the page");

      // On the screen, flipped, the frame's left quarter shows the image's blue half and its right quarter the red one.
      const BLUE = [0, 40, 220], RED = [220, 40, 0], dim = (rgb) => rgb.map((v) => v * 0.88);
      const colourAt = async (pt) => (await screenPixels(page, { x: pt.x - 2, y: pt.y - 2, width: 4, height: 4 })).median;
      // the colours at the two quarters once they are `want` (an image or a mask may take a frame to draw)
      const shows = async (want, what) => {
        let seen = null;
        for (let i = 0; i < 20; i++) {
          seen = [await colourAt(left), await colourAt(right)];
          if (seen.every((c, j) => sameColour(c, want[j], 12))) return;
          await page.waitForTimeout(150);
        }
        assert(false, `${what}: ${JSON.stringify({ seen, want: want.map((c) => c.map(Math.round)) })}`);
      };
      const filter = () => zoomed.locator("img").evaluate((el) => getComputedStyle(el).filter);
      const flipped = (on) => panel().evaluate((el, on) => el.classList.toggle("pdfDark", on), on);
      await shows([BLUE, RED], "a light page");
      await flipped(true);
      assertEq(await filter(), "brightness(0.88)", "dimmed with the page's colours flipped");
      await shows([dim(BLUE), dim(RED)], "a flipped page: dimmed, not inverted");
      await flipped(false);
      const theme = await page.evaluate(() => document.documentElement.dataset.theme);
      await page.evaluate(() => { document.documentElement.dataset.theme = "gamma-dark"; });
      assertEq(await filter(), "brightness(0.88)", "dimmed in Gamma Dark");
      await shows([dim(BLUE), dim(RED)], "Gamma Dark: dimmed, not inverted");
      await page.evaluate((was) => {
        if (was == null) delete document.documentElement.dataset.theme;
        else document.documentElement.dataset.theme = was;
      }, theme ?? null);

      // the pen writes across it, the picture as it was; a highlighter across its left quarter multiplies into it, on a flipped page too
      await draw(await inPanel(cx - 45, cy + 8), await inPanel(cx - 5, cy + 8));
      await until(async () => (await savedStrokes()).length === 1, { what: "the pen's stroke saved" });
      assert(await armHighlighter(page), "a highlighter preset is armed");
      const lineY = cy - 0.25 * pic.w * Math.sin(a);
      await draw(await inPanel(cx - 45, lineY), await inPanel(cx - 5, lineY));
      const { stroke } = await until(async () => (await savedStrokes()).find((s) => s.stroke.tool === "highlighter"), { what: "the highlighter saved" });
      await until(async () => (await panel().locator(".inkOverPictures use").count()) === 1, { what: "the highlighter drawn again over the panel's picture" });
      await shows([highlighted(stroke, BLUE), RED], "a highlighter over it on a light page");
      await flipped(true);
      await shows([highlighted(stroke, dim(BLUE)), dim(RED)], "a highlighter over it on a flipped page");
      await flipped(false);
      const stored = (await account.api(`/api/blocks/${pageId}/subtree`)).block.children.find((b) => b.id === "zoomPic");
      assertEq(JSON.stringify(stored.properties.picture), JSON.stringify(pic), "the picture as it was");

      // a move of the picture shows in the panel at once
      await setPicture({ op: "set", id: "zoomPic", props: { picture: { ...pic, x: pic.x + 10 } } });
      await until(async () => Math.abs((await zoomed.boundingBox()).x - (shown.x + 10 * z.mag)) <= 1.5, { what: "the panel's picture moved with it" });
      near((await zoomed.boundingBox()).y, shown.y, 1, "across only");

      // placed half a minute into the recording: its player, from the start, shades it on the page and in the panel; Done takes the shade off both
      await setPicture({ op: "set", id: "zoomPic", props: { t0: started + 30_000 } });
      const shaded = async () => [await pagePic.evaluate((el) => el.classList.contains("replayPending")),
        await zoomed.evaluate((el) => el.classList.contains("replayPending"))];
      assertEq(JSON.stringify(await shaded()), "[false,false]", "no shade with no player");
      await page.click(".recordingCard button[aria-label='Play recording']");
      await page.waitForSelector(".replayBar", { timeout: 5000 });
      await until(async () => (await shaded()).every(Boolean), { what: "the picture shaded before its moment, on the page and in the panel" });
      await page.click(".replayBar .replayClose");
      await until(async () => !(await page.$(".replayBar")), { what: "the player closed" });
      assertEq(JSON.stringify(await shaded()), "[false,false]", "the shade gone from both with the player");

      // a second picture after it in the notes, sent under it by its z: the panel stacks the two as the page does
      await setPicture({ op: "insert", id: "zoomUnder", parent: pageId, content: "", props: { picture: { ...pic, z: -1 }, pdf_page: 1 } });
      const pagePics = page.locator(".pdfViewer .pagePicture[data-picture-id]");
      await until(async () => (await pagePics.evaluateAll((els) => els.map((el) => el.dataset.pictureId))).join(" ") === "zoomUnder zoomPic",
        { what: "the page's pictures by their z, the later one under" });
      const frames = (pics) => pics.evaluateAll((els) => els.map((el) => el.getAttribute("style")));
      await until(async () => JSON.stringify(await frames(zoomed)) === JSON.stringify(await frames(pagePics)),
        { what: "the panel's pictures stacked as the page's" });
      assertNoProblems(page);
    } finally { await pctx.close(); }
  });
}
