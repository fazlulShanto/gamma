// Privileged chrome is a specific bundled page in a specific top-level view,
// never an arbitrary file: URL or a subframe claiming the chrome's address.
const path = require('path');
const { pathToFileURL } = require('url');

const pages = Object.fromEntries(['bar', 'launcher'].map((name) =>
  [name, pathToFileURL(path.join(__dirname, '..', 'ui', `${name}.html`)).href]));

function isShellPage(raw, name) {
  try {
    const url = new URL(raw);
    url.search = '';
    url.hash = '';
    return url.href === pages[name];
  } catch { return false; }
}

function trustedShellSender(event, bar, content) {
  if (!event.senderFrame || !event.sender || event.senderFrame !== event.sender.mainFrame) return false;
  return (event.sender === bar?.webContents && isShellPage(event.senderFrame.url, 'bar'))
    || (event.sender === content?.webContents && isShellPage(event.senderFrame.url, 'launcher'));
}

module.exports = { pages, isShellPage, trustedShellSender };
