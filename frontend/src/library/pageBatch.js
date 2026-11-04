// Many pages, one request each (the library's bulk actions: delete, move,
// pin, label, copy). From ROW_FROM (10) items on, the work is a row of
// Background tasks — "12 of 340 pages", and Stop, which ends it before the
// next item; up to POOL (4) requests at once where order does not matter.
// Resolves {done, failed, stopped}: the items `run` finished, the errors of
// the ones it could not, and whether the person stopped it. The batch
// lives in the tab: a reload or a closed tab ends it part way.
// tests/pageBatch.test.mjs.
import { tn } from "../shared/i18n/i18n.js";
import { progressCounts } from "../tasks/taskKinds.js";

export const ROW_FROM = 10;
export const POOL = 4;

const failures = (failed) => tn("{n} page could not be changed: {message}", "{n} pages could not be changed: {message}",
  failed.length, { message: failed[0].message });

export async function runBatch(items, run, { name, tasks, pool = POOL }) {
  const total = items.length;
  const done = [], failed = [];
  let next = 0, stop = false, shown = 0, row = null;
  const counts = () => progressCounts({ done: done.length + failed.length, total, unit: "pages" });
  if (total >= ROW_FROM) {
    row = tasks.addLocal({ kind: "batch", name, info: counts(), fraction: 0, cancel: () => { stop = true; } });
  }
  // One patch per visible percent (each one re-renders the app), none
  // once the row was stopped.
  const report = () => {
    const fraction = (done.length + failed.length) / total;
    const pct = Math.floor(fraction * 100);
    if (row === null || stop || pct === shown) return;
    shown = pct;
    tasks.updateLocal(row, { info: counts(), fraction });
  };
  const worker = async () => {
    while (!stop && next < total) {
      const item = items[next++];
      try {
        await run(item);
        done.push(item);
      } catch (err) {
        failed.push(err);
      }
      report();
    }
  };
  await Promise.all(Array.from({ length: Math.min(pool, total) }, worker));
  // A stopped row is "cancelled" already, and the result says so too, even
  // when the Stop came as the last items ran: the row and the status agree.
  if (row !== null && !stop) {
    tasks.updateLocal(row, failed.length ? { state: "failed", info: failures(failed), cancel: null }
      : { state: "done", info: counts(), cancel: null });
  }
  return { done, failed, stopped: stop };
}

// A batch's closing status: `said`, what the work did, then where a Stop
// left it and what could not be done, with the first error — that alone
// when nothing was done. "" when there is nothing to say.
export function batchText(said, { done, failed, stopped }, total) {
  const note = [
    stopped ? tn("Stopped after {done} of {n} page.", "Stopped after {done} of {n} pages.", total,
      { done: done.length + failed.length }) : "",
    failed.length ? failures(failed) : "",
  ].filter(Boolean).join(" ");
  return done.length || !note ? [said, note].filter(Boolean).join(" ") : note;
}
