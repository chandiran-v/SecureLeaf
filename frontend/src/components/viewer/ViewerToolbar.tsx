import { Link } from 'react-router-dom';
import { MAX_ZOOM, MIN_ZOOM, useViewerStore } from '../../stores/viewerStore';

interface ViewerToolbarProps {
  productId: string;
  currentPage: number;
  pageCount: number | null;
  onPrev: () => void;
  onNext: () => void;
}

const buttonClass =
  'rounded-lg px-3 py-1.5 text-sm font-medium text-gray-200 transition-colors hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-30';

/** D10 — a subtle glass toolbar; D6 — Prev/Next plus the "Page n / N" indicator; zoom controls. */
export default function ViewerToolbar({ productId, currentPage, pageCount, onPrev, onNext }: ViewerToolbarProps) {
  const pageLabel = pageCount != null ? `Page ${currentPage} / ${pageCount}` : `Page ${currentPage}`;
  const zoom = useViewerStore((state) => state.zoom);
  const zoomIn = useViewerStore((state) => state.zoomIn);
  const zoomOut = useViewerStore((state) => state.zoomOut);
  const resetZoom = useViewerStore((state) => state.resetZoom);

  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-white/10 bg-white/5 px-4 py-3 backdrop-blur-sm sm:px-6">
      <Link
        to={`/product/${productId}`}
        className="text-sm font-medium text-gray-300 transition-colors hover:text-white"
      >
        ← Exit
      </Link>

      <div className="flex items-center gap-3">
        <button onClick={onPrev} disabled={currentPage <= 1} aria-label="Previous page" className={buttonClass}>
          ← Prev
        </button>
        <span className="min-w-[6rem] text-center text-sm text-gray-300">{pageLabel}</span>
        <button
          onClick={onNext}
          disabled={pageCount != null && currentPage >= pageCount}
          aria-label="Next page"
          className={buttonClass}
        >
          Next →
        </button>
      </div>

      <div className="flex items-center gap-1" role="group" aria-label="Zoom">
        <button onClick={zoomOut} disabled={zoom <= MIN_ZOOM} aria-label="Zoom out" className={buttonClass}>
          −
        </button>
        <span className="min-w-[3.5rem] text-center text-sm tabular-nums text-gray-300" aria-live="polite" data-testid="zoom-level">
          {Math.round(zoom * 100)}%
        </span>
        <button onClick={zoomIn} disabled={zoom >= MAX_ZOOM} aria-label="Zoom in" className={buttonClass}>
          +
        </button>
        <button
          onClick={resetZoom}
          disabled={zoom === 1}
          aria-label="Fit page to screen"
          title="Fit page (0)"
          className={buttonClass}
        >
          Fit
        </button>
        <span className="ml-2 hidden text-xs text-gray-500 lg:block">← → pages · + − 0 zoom</span>
      </div>
    </div>
  );
}
