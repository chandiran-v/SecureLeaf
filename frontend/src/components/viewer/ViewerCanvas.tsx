import { useEffect, useLayoutEffect, useRef, type PointerEvent, type RefObject } from 'react';
import { useViewerStore } from '../../stores/viewerStore';
import { computeFitWidth } from '../../lib/viewerLayout';
import type { PageLink } from '../../types';

interface ViewerCanvasProps {
  canvasRef: RefObject<HTMLCanvasElement>;
  loading: boolean;
  printScreenBlanked: boolean;
  error?: boolean;
  onRetry?: () => void;
  /** The drawn page's clickable links (already filtered by `usableLinks`). */
  links?: PageLink[];
  /** Called for a link to another page of this document. */
  onGoToPage?: (page: number) => void;
}

/** How fast Ctrl+wheel / trackpad pinch zooms. exp() makes zooming in then out by the same
 *  wheel distance land exactly where it started. */
const WHEEL_ZOOM_SENSITIVITY = 0.002;

/**
 * D2 — the only element a page's pixels ever touch. No `<img>`, no CSS `background-image`, no
 * object URL: useSecureTile draws straight into this canvas from a decoded `ImageBitmap`.
 * VIEW-05/06 — select-none and draggable={false}/onDragStart prevented stop the two other
 * one-click ways to lift a page out of the DOM (text selection, drag-to-desktop).
 *
 * Zoom: the canvas's *backing store* (canvas.width/height, set by useSecureTile) stays at the
 * tile's resolution. Zooming only changes its *CSS* width: fit width × zoom. So zooming never
 * re-fetches a page, and past the tile's native size the browser upscales (a little soft). When
 * the page is bigger than the screen, the scroller scrolls, and dragging pans.
 *
 * Links (V8): the page is a picture, so the PDF's links are laid over it as invisible `<a>` /
 * `<button>` areas positioned in PERCENT of the page wrapper. The wrapper is exactly the canvas's
 * size, so the links line up at any zoom without recalculating anything.
 */
export default function ViewerCanvas({
  canvasRef,
  loading,
  printScreenBlanked,
  error,
  onRetry,
  links = [],
  onGoToPage,
}: ViewerCanvasProps) {
  const blurred = useViewerStore((state) => state.focusBlurred || state.devToolsBlurred);
  const zoom = useViewerStore((state) => state.zoom);
  const hide = blurred || printScreenBlanked;
  const scrollerRef = useRef<HTMLDivElement>(null);
  const panStart = useRef<{ x: number; y: number; left: number; top: number } | null>(null);

  // Size the canvas: recompute when zoom changes, when the reading area resizes, and when a
  // new page with a different shape is drawn (canvas width/height attributes change).
  useLayoutEffect(() => {
    const scroller = scrollerRef.current;
    const canvas = canvasRef.current;
    if (!scroller || !canvas) return;

    const applySize = () => {
      if (!canvas.width || !canvas.height) return;
      const style = window.getComputedStyle(scroller);
      const availableWidth =
        scroller.clientWidth - parseFloat(style.paddingLeft || '0') - parseFloat(style.paddingRight || '0');
      const availableHeight =
        scroller.clientHeight - parseFloat(style.paddingTop || '0') - parseFloat(style.paddingBottom || '0');
      const fitWidth = computeFitWidth(availableWidth, availableHeight, canvas.width / canvas.height);
      if (fitWidth > 0) canvas.style.width = `${fitWidth * zoom}px`;
    };

    applySize();
    const attributeObserver = new MutationObserver(applySize);
    attributeObserver.observe(canvas, { attributes: true, attributeFilter: ['width', 'height'] });
    const resizeObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(applySize) : null;
    resizeObserver?.observe(scroller);
    return () => {
      attributeObserver.disconnect();
      resizeObserver?.disconnect();
    };
  }, [canvasRef, zoom]);

  // Ctrl/Cmd + wheel (and trackpad pinch, which browsers report as Ctrl + wheel) zooms the page
  // instead of the whole browser tab. Needs a non-passive native listener: React's onWheel is
  // passive, so preventDefault() there can't stop the browser's own zoom.
  useEffect(() => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return; // plain wheel keeps scrolling the page
      event.preventDefault();
      const { zoom: current, setZoom } = useViewerStore.getState();
      setZoom(current * Math.exp(-event.deltaY * WHEEL_ZOOM_SENSITIVITY));
    };
    scroller.addEventListener('wheel', onWheel, { passive: false });
    return () => scroller.removeEventListener('wheel', onWheel);
  }, []);

  // Drag to pan when the zoomed page is larger than the screen.
  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    const scroller = scrollerRef.current;
    if (!scroller || event.button !== 0) return;
    // Pressing on a link must click it, not start a pan: pointer capture would retarget the
    // click to the scroller and the link would never fire.
    if ((event.target as HTMLElement).closest('a, button')) return;
    if (scroller.scrollWidth <= scroller.clientWidth && scroller.scrollHeight <= scroller.clientHeight) return;
    panStart.current = { x: event.clientX, y: event.clientY, left: scroller.scrollLeft, top: scroller.scrollTop };
    scroller.setPointerCapture?.(event.pointerId);
  };
  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const scroller = scrollerRef.current;
    const start = panStart.current;
    if (!scroller || !start) return;
    scroller.scrollLeft = start.left - (event.clientX - start.x);
    scroller.scrollTop = start.top - (event.clientY - start.y);
  };
  const endPan = () => {
    panStart.current = null;
  };

  return (
    <div className="relative min-h-0 flex-1 select-none bg-gray-950">
      {/* `m-auto` (not justify/items-center) centres the page while it fits, yet lets it scroll
          fully once it overflows — flex centring would cut off the top/left of a zoomed page. */}
      <div
        ref={scrollerRef}
        data-testid="viewer-scroller"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endPan}
        onPointerCancel={endPan}
        className={`absolute inset-0 flex overflow-auto p-4 ${zoom > 1 ? 'cursor-grab active:cursor-grabbing' : ''}`}
      >
        <div className="relative m-auto shrink-0" data-testid="viewer-page">
          <canvas
            ref={canvasRef}
            draggable={false}
            onDragStart={(event) => event.preventDefault()}
            className={`block h-auto rounded shadow-2xl transition-[filter] duration-150 ${hide ? 'blur-3xl' : ''}`}
          />

          {/* Hidden with the page (blur / PrintScreen / loading) so links never float over nothing. */}
          {!hide && !loading &&
            links.map((link, index) => {
              const style = {
                left: `${link.left * 100}%`,
                top: `${link.top * 100}%`,
                width: `${link.width * 100}%`,
                height: `${link.height * 100}%`,
              };
              const className =
                'absolute block rounded-sm transition-colors hover:bg-emerald-400/20 focus-visible:bg-emerald-400/25 focus-visible:outline focus-visible:outline-2 focus-visible:outline-emerald-400';
              if (link.type === 'URL' && link.url) {
                return (
                  <a
                    key={index}
                    href={link.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    title={link.url}
                    aria-label={`${link.url} (opens in a new tab)`}
                    draggable={false}
                    style={style}
                    className={className}
                  />
                );
              }
              if (link.type === 'PAGE' && link.targetPage != null) {
                const target = link.targetPage;
                return (
                  <button
                    key={index}
                    type="button"
                    onClick={() => onGoToPage?.(target)}
                    title={`Go to page ${target}`}
                    aria-label={`Go to page ${target}`}
                    style={style}
                    className={`${className} cursor-pointer`}
                  />
                );
              }
              return null;
            })}
        </div>
      </div>

      {hide && (
        <div className="absolute inset-0 flex items-center justify-center bg-gray-950/90 text-sm text-gray-300">
          {!printScreenBlanked && 'Reading paused'}
        </div>
      )}

      {loading && !hide && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="h-10 w-10 rounded-full border-2 border-gray-700 border-t-emerald-500 animate-spin" />
        </div>
      )}

      {error && !hide && !loading && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-gray-950/90 text-sm text-gray-300" role="alert">
          <p>Couldn&apos;t load this page.</p>
          {onRetry && (
            <button onClick={onRetry} className="rounded-lg bg-white/10 px-4 py-1.5 font-medium text-white hover:bg-white/20">
              Retry
            </button>
          )}
        </div>
      )}
    </div>
  );
}
