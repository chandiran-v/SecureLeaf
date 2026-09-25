import { Link } from 'react-router-dom';

interface ViewerToolbarProps {
  productId: string;
  currentPage: number;
  pageCount: number | null;
  onPrev: () => void;
  onNext: () => void;
}

/** D10 — a subtle glass toolbar; D6 — Prev/Next plus the "Page n / N" indicator. */
export default function ViewerToolbar({ productId, currentPage, pageCount, onPrev, onNext }: ViewerToolbarProps) {
  const pageLabel = pageCount != null ? `Page ${currentPage} / ${pageCount}` : `Page ${currentPage}`;

  return (
    <div className="flex items-center justify-between gap-4 border-b border-white/10 bg-white/5 px-4 py-3 backdrop-blur-sm sm:px-6">
      <Link
        to={`/product/${productId}`}
        className="text-sm font-medium text-gray-300 transition-colors hover:text-white"
      >
        ← Exit
      </Link>

      <div className="flex items-center gap-3">
        <button
          onClick={onPrev}
          disabled={currentPage <= 1}
          aria-label="Previous page"
          className="rounded-lg px-3 py-1.5 text-sm font-medium text-gray-200 transition-colors hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-30"
        >
          ← Prev
        </button>
        <span className="min-w-[6rem] text-center text-sm text-gray-300">{pageLabel}</span>
        <button
          onClick={onNext}
          disabled={pageCount != null && currentPage >= pageCount}
          aria-label="Next page"
          className="rounded-lg px-3 py-1.5 text-sm font-medium text-gray-200 transition-colors hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-30"
        >
          Next →
        </button>
      </div>

      <span className="hidden text-xs text-gray-500 sm:block">Use ← → to turn pages</span>
    </div>
  );
}
