import { createRef } from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import ViewerCanvas from './ViewerCanvas';
import { computeFitWidth } from '../../lib/viewerLayout';
import { useViewerStore } from '../../stores/viewerStore';

describe('computeFitWidth', () => {
  it('is limited by height for a portrait page on a wide screen', () => {
    // 1000×800 area, A4-ish page (aspect 0.7): height runs out first → 800 × 0.7
    expect(computeFitWidth(1000, 800, 0.7)).toBeCloseTo(560);
  });

  it('is limited by width for a wide page on a narrow screen', () => {
    expect(computeFitWidth(360, 800, 1.5)).toBe(360);
  });

  it('is 0 (leave the canvas alone) when there is nothing to measure yet', () => {
    expect(computeFitWidth(0, 800, 0.7)).toBe(0);
    expect(computeFitWidth(1000, 800, Number.NaN)).toBe(0);
  });
});

describe('ViewerCanvas zoom', () => {
  const originalClientWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth');
  const originalClientHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight');

  beforeEach(() => {
    useViewerStore.getState().reset(1);
    // jsdom does no layout: give every element a 1000×800 box so the fit maths has input.
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 1000 });
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => 800 });
  });

  afterEach(() => {
    if (originalClientWidth) Object.defineProperty(HTMLElement.prototype, 'clientWidth', originalClientWidth);
    if (originalClientHeight) Object.defineProperty(HTMLElement.prototype, 'clientHeight', originalClientHeight);
  });

  function renderCanvas() {
    const canvasRef = createRef<HTMLCanvasElement>();
    const utils = render(<ViewerCanvas canvasRef={canvasRef} loading={false} printScreenBlanked={false} />);
    return { canvasRef, ...utils };
  }

  it('sizes the canvas to fit, then scales that fit width by the zoom level', async () => {
    const { canvasRef } = renderCanvas();
    const canvas = canvasRef.current!;
    // What useSecureTile does after drawing a 700×1000 page (dpr 1). Async act: the
    // MutationObserver that notices the new width/height reports on a later microtask.
    await act(async () => {
      canvas.width = 700;
      canvas.height = 1000;
    });
    // Area is 1000×800 minus p-4 padding, which jsdom computes as 0 → fit = 800 × 0.7 = 560.
    expect(canvas.style.width).toBe('560px');

    act(() => useViewerStore.getState().setZoom(2));
    expect(canvas.style.width).toBe('1120px');

    act(() => useViewerStore.getState().resetZoom());
    expect(canvas.style.width).toBe('560px');
  });

  it('zooms the page (not the browser) on Ctrl + wheel, and zooms in when scrolling up', () => {
    renderCanvas();
    const scroller = screen.getByTestId('viewer-scroller');

    const notCancelled = fireEvent.wheel(scroller, { ctrlKey: true, deltaY: -100 });

    expect(notCancelled).toBe(false); // preventDefault() → the browser's own zoom is suppressed
    expect(useViewerStore.getState().zoom).toBeGreaterThan(1);
  });

  it('leaves a plain wheel alone so it keeps scrolling the page', () => {
    renderCanvas();
    const scroller = screen.getByTestId('viewer-scroller');

    const notCancelled = fireEvent.wheel(scroller, { deltaY: 100 });

    expect(notCancelled).toBe(true);
    expect(useViewerStore.getState().zoom).toBe(1);
  });
});
