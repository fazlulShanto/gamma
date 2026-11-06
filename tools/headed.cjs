// The one switch for a test run's windows. Every suite keeps them off the
// screen (docs/dev/debugging.md): the browser suites run headless, the desktop
// scripts keep the app's window minimized. `--headed` shows them for a run,
// GAMMA_TEST_HEADED=1 for a whole shell.
module.exports = process.argv.includes('--headed') || process.env.GAMMA_TEST_HEADED === '1';
