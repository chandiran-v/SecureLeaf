import type { TileVariant } from '../types';

/** The variant every client falls back to: the original full-resolution render. */
export const DEFAULT_TILE_VARIANT = 'DESKTOP';

/**
 * Phase 16, D5 — pick the smallest variant that still looks sharp.
 *
 * `neededWidth = canvasCssWidth × devicePixelRatio` is how many image pixels the canvas can show
 * 1:1. The smallest variant at least that wide wastes the least bandwidth; if none is wide enough
 * (a zoomed-in page, a 3x phone) the widest variant is the best available, and a server that
 * lacks the variant falls back to DESKTOP anyway.
 */
export function chooseTileVariant(
  variants: readonly TileVariant[] | null | undefined,
  canvasCssWidth: number,
  devicePixelRatio: number
): string {
  if (!variants || variants.length === 0) return DEFAULT_TILE_VARIANT;
  const neededWidth = Math.max(0, canvasCssWidth) * Math.max(1, devicePixelRatio || 1);
  const sorted = [...variants].sort((a, b) => a.widthPx - b.widthPx);
  const fits = sorted.find((variant) => variant.widthPx >= neededWidth);
  if (fits) return fits.name;
  // Nothing is wide enough: DESKTOP (the original) if offered, else the widest there is.
  return variants.some((v) => v.name === DEFAULT_TILE_VARIANT)
    ? DEFAULT_TILE_VARIANT
    : sorted[sorted.length - 1].name;
}
