// Real renderers with synthetic data: no backend, accounts or saved profile.
// node tests/e2e/markdownSecurity.mjs (from frontend/).
import assert from "node:assert/strict";
import { build } from "esbuild";
import { chromium } from "playwright";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const bundle = await build({
  stdin: { resolveDir: root, loader: "jsx", contents: `
    import React from 'react';
    import {createRoot} from 'react-dom/client';
    import {flushSync} from 'react-dom';
    import {BlockMarkdown} from './src/editor/BlockTree.jsx';
    import Markdown from 'react-markdown';
    import raw from 'rehype-raw';
    const root = createRoot(document.getElementById('note'));
    window.renderNote = (content) => flushSync(() => root.render(<BlockMarkdown content={content} refLabels={{}} />));
    window.renderUnsafeFixture = (content) => flushSync(() => root.render(<Markdown rehypePlugins={[raw]}>{content}</Markdown>));
  ` },
  bundle: true, write: false, format: "iife",
  loader: { ".css": "empty", ".svg": "dataurl", ".png": "dataurl" },
  plugins: [{ name: "unused-worker-url", setup(b) {
    b.onResolve({ filter: /\?url$/ }, (args) => ({ path: args.path, namespace: "fixture-url" }));
    b.onLoad({ filter: /.*/, namespace: "fixture-url" }, () => ({ contents: 'export default "";' }));
  } }],
  define: { "process.env.NODE_ENV": '"production"' }, logLevel: "silent",
});
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  await page.route("**/*", (route) => route.fulfill({ contentType: "text/html", body: '<!doctype html><div id="note"></div>' }));
  await page.goto("http://gamma-security.invalid/");
  await page.addScriptTag({ content: bundle.outputFiles[0].text });
  const attack = '<iframe srcdoc="<script>parent.__noteProbe = 1</script>"></iframe>';
  // Demonstrate the old pipeline's executable srcdoc on the same browser.
  await page.evaluate((source) => window.renderUnsafeFixture(source), attack);
  await page.waitForFunction(() => window.__noteProbe === 1);
  await page.evaluate((source) => { delete window.__noteProbe; window.renderNote(source); }, attack);
  assert.equal(await page.locator('#note iframe').count(), 0);
  assert.equal(await page.evaluate(() => window.__noteProbe), undefined);

  await page.evaluate(() => window.renderNote('> [!tip]+ Title\n> <span style="color:#e5484d;background:#e5484d55">Color</span> and $x^2$\n\n- [x] task\n\n```svg width=240\n<svg viewBox="0 0 10 10"><rect width="10" height="10"/></svg>\n```'));
  await page.waitForSelector('#note .diagramImg');
  assert.equal(await page.locator('#note details[open] summary').textContent(), 'Title');
  assert.equal(await page.locator('#note .katex').count(), 1);
  assert.equal(await page.locator('#note input[type=checkbox]').isChecked(), true);
  assert.equal(await page.locator('#note span[style]').first().evaluate((el) => el.style.color), 'rgb(229, 72, 77)');

  const bar = await browser.newPage();
  const role = '<img src="invalid" onerror="window.__barProbe=1">';
  const state = { servers: [], current: { id: 'remote', type: 'remote', name: 'Remote' },
    gamma: { current: 'workspace', list: [{ id: 'workspace', name: 'Shared', role, members: 1 }] } };
  await bar.addInitScript((state) => {
    window.gammaShell = { platform: 'win32', state: async () => state, onState: () => {}, barExpand: () => {} };
  }, state);
  const barHtml = readFileSync(path.join(root, '../desktop/ui/bar.html'), 'utf8');
  await bar.route('**/*', (route) => route.fulfill({ contentType: route.request().url().endsWith('.css') ? 'text/css' : 'text/html',
    body: route.request().url().endsWith('.css') ? '' : barHtml }));
  await bar.goto('http://gamma-security.invalid/bar.html');
  await bar.waitForSelector('.wsItem', { state: 'attached' });
  assert.equal(await bar.locator('.wsItem .sub').textContent(), role);
  assert.equal(await bar.locator('.wsItem img').count(), 0);
  assert.equal(await bar.evaluate(() => window.__barProbe), undefined);
  console.log('PASS: note srcdoc exploit reproduced, production notes sanitize it, rich formatting works, desktop role is inert text.');
} finally {
  await browser.close();
}
