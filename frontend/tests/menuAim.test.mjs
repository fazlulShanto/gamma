// The safe-triangle geometry behind submenu hover intent: is a pointer that
// was at (fromX, fromY) and is now at (x, y) heading into the open panel?
import assert from "node:assert/strict";
import { test } from "node:test";
import { isAimingAt } from "../src/shared/ui/menuAim.js";

const panel = { left: 200, right: 400, top: 50, bottom: 150 };

test("a move toward the panel's near edge is an aim; away or past it is not", () => {
  assert.equal(isAimingAt(150, 100, 100, 100, panel), true, "straight at it");
  assert.equal(isAimingAt(150, 80, 100, 100, panel), true, "diagonally up, still inside the triangle");
  assert.equal(isAimingAt(150, 60, 100, 100, panel), false, "too steep: above the triangle's upper edge");
  assert.equal(isAimingAt(150, 300, 100, 100, panel), false, "sliding down the parent menu");
  assert.equal(isAimingAt(50, 100, 100, 100, panel), false, "moving away");
  assert.equal(isAimingAt(250, 100, 100, 100, panel), false, "already past the edge: the panel's own hover takes over");
});

test("the triangle is built toward whichever side the panel is on, padded by slack", () => {
  assert.equal(isAimingAt(450, 100, 500, 100, panel), true, "a panel opened to the left");
  assert.equal(isAimingAt(199, 45, 100, 100, panel, 6), true, "just above the corner, within the slack");
  assert.equal(isAimingAt(199, 30, 100, 100, panel, 6), false, "well above the corner");
});

test("a card below or above the cursor is aimed at through its top or bottom edge", () => {
  // A citation's preview under its pill: the pill spans x 200–240 just above it.
  assert.equal(isAimingAt(220, 46, 220, 40, panel), true, "straight down at it");
  assert.equal(isAimingAt(260, 46, 220, 40, panel), true, "down and to the right, toward its far corner");
  assert.equal(isAimingAt(190, 42, 220, 40, panel), false, "sideways along the line, past the corner");
  assert.equal(isAimingAt(220, 30, 220, 40, panel), false, "back up");
  assert.equal(isAimingAt(220, 154, 220, 160, panel), true, "a card above, aimed at from below");
  assert.equal(isAimingAt(220, 170, 220, 160, panel), false, "away from the card above");
  // Off its corner, the edge the cursor is farther out from faces it.
  assert.equal(isAimingAt(400, 30, 410, 20, panel), true, "from above and just right of it, through the top edge");
  assert.equal(isAimingAt(415, 45, 430, 40, panel), true, "from beside and just above it, through the side edge");
});

test("no panel, or a pointer parked on its edge, never counts as aiming", () => {
  assert.equal(isAimingAt(150, 100, 100, 100, null), false);
  assert.equal(isAimingAt(200, 120, 200, 100, panel), false, "degenerate triangle");
});
