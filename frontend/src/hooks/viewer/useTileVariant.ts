import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { chooseTileVariant, DEFAULT_TILE_VARIANT } from '../../lib/tileVariant';
import type { TileVariant } from '../../types';

/** Resize events fire continuously while dragging a window edge; wait for the user to stop. */
export const VARIANT_DEBOUNCE_MS = 200;

/**
 * Phase 16, D5 — which tile variant the reader should request right now. Evaluated once on mount
 * and again, debounced, whenever the window resizes or the device rotates, because both change
 * the canvas's CSS width. The returned name only changes when the answer does, so a resize that
 * keeps the same variant triggers no refetch.
 *
 * Before the first page is drawn the canvas has no width yet, so the window width stands in:
 * the page is at most that wide.
 */
export function useTileVariant(
  variants: readonly TileVariant[] | null | undefined,
  canvasRef: RefObject<HTMLCanvasElement>
): string {
  const variantsRef = useRef(variants);
  variantsRef.current = variants;

  const evaluate = () =>
    chooseTileVariant(
      variantsRef.current,
      canvasRef.current?.clientWidth || window.innerWidth,
      window.devicePixelRatio || 1
    );

  const [variant, setVariant] = useState<string>(variants?.length ? evaluate() : DEFAULT_TILE_VARIANT);

  // The session (and so the variant list) arrives after the first render.
  useEffect(() => {
    setVariant(evaluate());
    // eslint-disable-next-line react-hooks/exhaustive-deps -- evaluate reads refs only
  }, [variants]);

  useEffect(() => {
    let timer: number | undefined;
    const onChange = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => setVariant(evaluate()), VARIANT_DEBOUNCE_MS);
    };
    window.addEventListener('resize', onChange);
    window.addEventListener('orientationchange', onChange);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener('resize', onChange);
      window.removeEventListener('orientationchange', onChange);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- evaluate reads refs only
  }, []);

  return variant;
}
