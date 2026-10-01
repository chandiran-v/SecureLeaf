import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useTileVariant, VARIANT_DEBOUNCE_MS } from './useTileVariant';

const VARIANTS = [
  { name: 'DESKTOP', widthPx: 1240 },
  { name: 'MOBILE', widthPx: 900 },
];
const noCanvas = { current: null };

function setViewport(width: number, dpr: number) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width });
  Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: dpr });
}

describe('useTileVariant (Phase 16, D5)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    setViewport(1024, 1);
  });

  it('requests MOBILE for a small viewport at DPR 1', () => {
    setViewport(390, 1);
    const { result } = renderHook(() => useTileVariant(VARIANTS, noCanvas));
    expect(result.current).toBe('MOBILE');
  });

  it('requests DESKTOP for a large viewport at DPR 2', () => {
    setViewport(1440, 2);
    const { result } = renderHook(() => useTileVariant(VARIANTS, noCanvas));
    expect(result.current).toBe('DESKTOP');
  });

  it('re-evaluates after a resize or rotation, debounced', () => {
    setViewport(390, 1);
    const { result } = renderHook(() => useTileVariant(VARIANTS, noCanvas));
    expect(result.current).toBe('MOBILE');

    setViewport(1440, 2);
    act(() => {
      window.dispatchEvent(new Event('resize'));
      window.dispatchEvent(new Event('resize'));
    });
    expect(result.current).toBe('MOBILE'); // still inside the debounce window
    act(() => {
      vi.advanceTimersByTime(VARIANT_DEBOUNCE_MS);
    });
    expect(result.current).toBe('DESKTOP');

    setViewport(390, 1);
    act(() => {
      window.dispatchEvent(new Event('orientationchange'));
      vi.advanceTimersByTime(VARIANT_DEBOUNCE_MS);
    });
    expect(result.current).toBe('MOBILE');
  });

  it('uses the canvas width once a page is drawn', () => {
    setViewport(1440, 1);
    const canvas = { clientWidth: 400 } as HTMLCanvasElement;
    const { result } = renderHook(() => useTileVariant(VARIANTS, { current: canvas }));
    expect(result.current).toBe('MOBILE');
  });

  it('stays on DESKTOP until the session supplies variants', () => {
    setViewport(390, 1);
    const { result, rerender } = renderHook(({ variants }) => useTileVariant(variants, noCanvas), {
      initialProps: { variants: undefined as typeof VARIANTS | undefined },
    });
    expect(result.current).toBe('DESKTOP');
    rerender({ variants: VARIANTS });
    expect(result.current).toBe('MOBILE');
  });
});
