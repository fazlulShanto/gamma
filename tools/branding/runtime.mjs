import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

export const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
export const { chromium } = require('playwright');

export async function renderPng(browser, svg, width, height, transparent = true) {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  try {
    // Rendering is offline: no Google Fonts or other network dependencies.
    await page.route('**/*', route => route.abort());
    await page.setContent(`<style>html,body{margin:0;background:transparent}svg{display:block}</style>${svg}`);
    await page.evaluate(() => document.fonts.ready);
    return await page.screenshot({ omitBackground: transparent });
  } finally {
    await page.close();
  }
}

// Chromium writes a screenshot with no transparent pixel as 24-bit RGB, even
// with omitBackground. This re-encodes such a PNG as 32-bit RGBA, every pixel
// opaque and its colour unchanged, for a store that asks for an alpha channel.
export function withAlpha(png) {
  if (png[24] !== 8 || png[25] !== 2 || png[28] !== 0) throw new Error('Expected an 8-bit RGB PNG, not interlaced');
  const width = png.readUInt32BE(16), height = png.readUInt32BE(20), stride = width * 3;
  const idat = [];
  for (let at = 8; at < png.length; at += 12 + png.readUInt32BE(at)) {
    if (png.toString('latin1', at + 4, at + 8) === 'IDAT') idat.push(png.subarray(at + 8, at + 8 + png.readUInt32BE(at)));
  }
  const rows = zlib.inflateSync(Buffer.concat(idat));
  const rgba = Buffer.alloc(height * (1 + width * 4)); // each row: filter 0 (none), then its pixels
  let above = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = rows[y * (1 + stride)], row = rows.subarray(y * (1 + stride) + 1, (y + 1) * (1 + stride));
    for (let x = 0; x < stride; x++) {
      // Undo the row's filter in place (PNG filter method 0).
      const a = x < 3 ? 0 : row[x - 3], b = above[x], c = x < 3 ? 0 : above[x - 3];
      const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      row[x] += filter === 1 ? a : filter === 2 ? b : filter === 3 ? (a + b) >> 1
        : filter === 4 ? (pa <= pb && pa <= pc ? a : pb <= pc ? b : c) : 0;
      rgba[y * (1 + width * 4) + 1 + (x - x % 3) / 3 * 4 + x % 3] = row[x];
    }
    for (let x = 0; x < width; x++) rgba[y * (1 + width * 4) + 4 + x * 4] = 255;
    above = row;
  }
  const chunk = (type, data) => {
    const bytes = Buffer.concat([Buffer.alloc(4), Buffer.from(type, 'latin1'), data, Buffer.alloc(4)]);
    bytes.writeUInt32BE(data.length, 0);
    bytes.writeUInt32BE(zlib.crc32(bytes.subarray(4, 8 + data.length)), 8 + data.length);
    return bytes;
  };
  const header = Buffer.from(png.subarray(16, 29));
  header[9] = 6; // colour type 6: RGB with alpha
  return Buffer.concat([png.subarray(0, 8), chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(rgba)), chunk('IEND', Buffer.alloc(0))]);
}
