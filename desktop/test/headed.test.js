// The one switch for a test run's windows (tools/headed.cjs), and that every
// suite goes through it.
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const ROOT = path.join(__dirname, '..', '..');
const SWITCH = path.join(ROOT, 'tools', 'headed.cjs');

function headed(args, value) {
  const env = { ...process.env };
  delete env.GAMMA_TEST_HEADED;
  if (value !== undefined) env.GAMMA_TEST_HEADED = value;
  const code = `console.log(require(${JSON.stringify(SWITCH)}))`;
  return execFileSync(process.execPath, ['-e', code, '--', ...args], { env, encoding: 'utf8' }).trim() === 'true';
}

test('windows stay off the screen unless a run asks for them', () => {
  assert.equal(headed([]), false);
  assert.equal(headed(['--keep']), false);
  assert.equal(headed([], '0'), false);
  assert.equal(headed(['--headed']), true);
  assert.equal(headed([], '1'), true);
});

test('no suite decides for itself', () => {
  const dirs = ['frontend/tests/e2e', 'frontend/tests/e2e/scenarios', 'extension/tests', 'desktop/test'];
  const sources = dirs.flatMap((dir) => fs.readdirSync(path.join(ROOT, dir))
    .filter((f) => /\.(mjs|js)$/.test(f) && f !== path.basename(__filename))
    .map((f) => path.join(ROOT, dir, f)));
  assert.ok(sources.length > 20, 'found the suites');
  for (const file of sources) {
    const text = fs.readFileSync(file, 'utf8');
    assert.doesNotMatch(text, /headless:\s*(true|false)|argv\.includes\(['"]--(headed|shown)/, path.relative(ROOT, file));
  }
});
