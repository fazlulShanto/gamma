// Redraw as shapes' and Convert to text's one request
// (docs/dev/handwriting_ai.md; gamma/routers/ink_ai.py): POST
// /api/ai/ink/shapes or /api/ai/ink/text with {model, space, strokes}
// (ink/inkAi.js inkAiRequest gives the space and strokes), the answer read
// as the NDJSON the server streams — `{"ping": 1}` lines while the model
// works, then one final line: {shapes}, {text}, or {error}.
import { API, proxyRefusal, readNdjson, withShare } from "../shared/lib/utils.js";

const KINDS = new Set(["shapes", "text"]);

// A refusal before the stream as shared/lib/utils.js apiError makes one:
// the server's `detail` (a proxy's 413 named as such), with its HTTP
// `status`, the parsed body as `data`, and `retryAfter` (seconds; the
// account's AI calls are all in use), as app/translateClient.js post()
// carries it.
async function refusal(res) {
  const text = await res.text().catch(() => "");
  let data = null;
  try { data = JSON.parse(text); } catch {}
  const detail = proxyRefusal(res.status, data) || (typeof data?.detail === "string" ? data.detail : text || `HTTP ${res.status}`);
  return Object.assign(new Error(detail), { status: res.status, data, retryAfter: Number(res.headers.get("Retry-After")) || 0 });
}

// Ask: `kind` "shapes" | "text", `body` {model, space, strokes}, `signal`
// the AbortSignal a cancel aborts. The fetch goes through the app's wrapper
// (utils.js), which adds the session's user, workspace and link-name
// headers, with the share token on the URL in a share view, and the
// cookie. Resolves with the final line ({shapes: […]} or {text}), or null
// when the stream ended without one; throws the refusal of a non-OK answer
// (an HTTP 429 of the AI allowance among them), an `error` line's message,
// and an abort's AbortError.
export async function askInkAi(kind, body, signal) {
  if (!KINDS.has(kind)) throw new TypeError(`unknown ink AI request: ${kind}`);
  const res = await fetch(withShare(`${API}/ai/ink/${kind}`), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    signal,
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await refusal(res);
  let final = null;
  await readNdjson(res, (events) => {
    for (const ev of events) {
      if (ev.error) throw new Error(ev.error);
      if (!("ping" in ev)) final = ev;
    }
  });
  return final;
}
