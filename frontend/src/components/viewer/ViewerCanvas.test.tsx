import { createRef } from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import ViewerCanvas from './ViewerCanvas';
import { computeFitWidth } from '../../lib/viewerLayout';
import { useViewerStore } from '../../stores/viewerStore';
import type { PageLink } from '../../types';

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

describe('ViewerCanvas links (V8)', () => {
  const urlLink: PageLink = { left: 0.1, top: 0.2, width: 0.3, height: 0.05, type: 'URL', url: 'https://example.com/more', targetPage: null };
  const pageLink: PageLink = { left: 0.1, top: 0.6, width: 0.2, height: 0.05, type: 'PAGE', url: null, targetPage: 4 };

  beforeEach(() => {
    useViewerStore.getState().reset(1);
  });

  function renderWithLinks(onGoToPage = vi.fn(), overrides: Partial<{ loading: boolean; printScreenBlanked: boolean }> = {}) {
    const canvasRef = createRef<HTMLCanvasElement>();
    render(
      <ViewerCanvas
        canvasRef={canvasRef}
        loading={overrides.loading ?? false}
        printScreenBlanked={overrides.printScreenBlanked ?? false}
        links={[urlLink, pageLink]}
        onGoToPage={onGoToPage}
      />
    );
    return onGoToPage;
  }

  it('overlays a web link as a new-tab <a>, positioned in percent of the page', () => {
    renderWithLinks();

    const link = screen.getByRole('link', { name: 'https://example.com/more (opens in a new tab)' });
    expect(link).toHaveAttribute('href', 'https://example.com/more');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link.style.left).toBe('10%');
    expect(link.style.top).toBe('20%');
    expect(link.style.width).toBe('30%');
    expect(link.style.height).toBe('5%');
    // Inside the page wrapper, so percentages follow the canvas's (zoomed) size.
    expect(screen.getByTestId('viewer-page')).toContainElement(link);
  });

  it('turns a link to another page into a button that jumps there', () => {
    const onGoToPage = renderWithLinks();

    fireEvent.click(screen.getByRole('button', { name: 'Go to page 4' }));

    expect(onGoToPage).toHaveBeenCalledWith(4);
  });

  it('hides links while the page is hidden or loading', () => {
    renderWithLinks(vi.fn(), { loading: true });
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('hides links when reading is paused (focus lost)', () => {
    renderWithLinks();
    act(() => useViewerStore.getState().setFocusBlurred(true));
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /go to page/i })).not.toBeInTheDocument();
  });
});
