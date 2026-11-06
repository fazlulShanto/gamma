// Real Electron boundary regression, with a throwaway profile and a fake
// loopback server; never starts a Gamma backend or touches a saved profile.
// The window stays minimized (GAMMA_SHELL_MINIMIZED); --shown shows it.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { _electron: electron } = require('playwright-core');

async function run() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'gamma-security-shell-'));
  const role = '<img src="invalid" onerror="window.__shellProbe=1">';
  const server = http.createServer((req, res) => {
    if (req.url.startsWith('/api/')) {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify(req.url.startsWith('/api/session')
        ? { user: 'tester', default_workspace: 'shared', workspaces: [{ id: 'shared', name: 'Shared', role, members: 1 }] }
        : {}));
    } else {
      res.setHeader('Content-Type', 'text/html');
      res.end('<!doctype html><p id="remote">Synthetic remote server</p>');
    }
  });
  let app;
  try {
    // The OS's pick, again if it is below 9100: those ports are the developer's,
    // and a dynamic range can start at 1024.
    do {
      if (server.listening) await new Promise((resolve) => server.close(resolve));
      await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    } while (server.address().port < 9100);
    const origin = `http://127.0.0.1:${server.address().port}`;
    const env = {
      ...process.env,
      GAMMA_SHELL_TEST: '1',
      GAMMA_SHELL_NO_UPDATE: '1',
      GAMMA_SHELL_USER_DATA: profile,
      ...(process.argv.includes('--shown') ? {} : { GAMMA_SHELL_MINIMIZED: '1' }),
    };
    delete env.ELECTRON_RUN_AS_NODE;
    app = await electron.launch({ args: [path.resolve(__dirname, '..')], timeout: 60_000, env });
    app.process().stderr.on('data', (d) => { if (String(d).includes('[shell]')) process.stderr.write(d); });
    async function pageMatching(match) {
      for (let i = 0; i < 100; i++) {
        const page = app.windows().find((p) => match(p.url()));
        if (page) return page;
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      const urls = await app.evaluate(({ webContents }) => webContents.getAllWebContents().map((wc) => wc.getURL()));
      throw new Error(`Expected Electron view did not load: ${JSON.stringify(urls)}`);
    }
    const launcher = await pageMatching((url) => url.includes('/ui/launcher.html'));
    assert.equal(await launcher.evaluate(() => typeof gammaShell.list), 'function');
    const remote = await launcher.evaluate((url) => gammaShell.addRemote('Security fixture', url), origin);
    await app.evaluate(async (_, id) => { await global.__gammaShell.openServer(id); }, remote.id);
    const content = await pageMatching((url) => url.startsWith(origin));
    assert.equal(await content.evaluate(() => typeof window.gammaShell), 'undefined');
    const bar = await pageMatching((url) => url.endsWith('/ui/bar.html'));
    await bar.evaluate(() => gammaShell.state());
    await bar.waitForSelector('.wsItem', { state: 'attached' });
    assert.equal(await bar.locator('.wsItem .sub').textContent(), role);
    assert.equal(await bar.locator('.wsItem img').count(), 0);
    assert.equal(await bar.evaluate(() => window.__shellProbe), undefined);

    const file = path.join(profile, 'untrusted.html');
    fs.writeFileSync(file, '<!doctype html><p>Untrusted local file</p>');
    const fileUrl = pathToFileURL(file).href;
    await content.evaluate((url) => { window.location.href = url; }, fileUrl);
    await new Promise((resolve) => setTimeout(resolve, 200));
    assert.ok(content.url().startsWith(origin), 'remote page cannot navigate into local files');
    // Direct main-process loads do not emit will-navigate; even then an
    // unrelated local file must never receive the bridge.
    await app.evaluate(async ({ webContents }, { origin, fileUrl }) => {
      const view = webContents.getAllWebContents().find((wc) => wc.getURL().startsWith(origin));
      await view.loadURL(fileUrl);
    }, { origin, fileUrl });
    await content.waitForURL(fileUrl);
    assert.equal(await content.evaluate(() => typeof window.gammaShell), 'undefined');
    console.log('PASS: bundled chrome bridge works, remote/local untrusted documents have no bridge, role HTML is inert, remote file navigation is blocked.');
  } finally {
    if (app) await app.close();
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    fs.rmSync(profile, { recursive: true, force: true });
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
