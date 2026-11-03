import fs from 'node:fs';
import { svgContent } from './svg-source.mjs';

const source = fs.readFileSync(new URL('../../design/brand/marks/favicon.svg', import.meta.url), 'utf8');
const PLATE = /<rect\b[^>]*\bid="gamma-background"[^>]*\/>/;
// The plate's colour, also the Android launcher icon's background layer.
export const plateColor = source.match(PLATE)[0].match(/\bfill="([^"]+)"/)[1];

// `bleed` (in the mark's 32-unit grid) makes a full-bleed square plate with the
// mark inset by that much: home-screen icons get their corners masked by the
// OS, so the plate must reach every edge (iOS paints transparent corners
// black), and a maskable icon keeps the mark inside the inner 80%.
// With `bare` the canvas stays transparent and only the inset applies: the
// Android launcher icon's foreground layer, which the system lays over the
// plate colour and masks.
// `mono` paints every shape white at its own opacity, for Android's themed
// icon layer, which the launcher tints by its alpha alone.
// Only presentation varies: all shapes, stroke weights and colors come from the favicon.
export function markSvg(size, { bare = false, mono = false, disabled = false, bleed = null } = {}) {
  let artwork = svgContent(source);
  if (bare) artwork = artwork.replace(PLATE, '');
  if (mono) artwork = artwork.replace(/#[0-9a-f]{6}\b/gi, '#ffffff');
  if (bleed != null) {
    const edge = 32 + 2 * bleed;
    const plate = bare ? '' : `<rect x="${-bleed}" y="${-bleed}" width="${edge}" height="${edge}" fill="${plateColor}"/>`;
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="${-bleed} ${-bleed} ${edge} ${edge}" fill="none">`
      + `${plate}${artwork}</svg>`;
  }
  if (disabled) artwork = artwork.replace(/#[0-9a-f]{6}\b/gi, color => {
    const rgb = [1, 3, 5].map(i => parseInt(color.slice(i, i + 2), 16));
    const gray = Math.round(rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722).toString(16).padStart(2, '0');
    return `#${gray.repeat(3)}`;
  });
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 32 32" fill="none">${artwork}</svg>`;
}
