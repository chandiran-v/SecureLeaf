import { useEffect, useRef, useState } from 'react';
import restClient from '../../lib/restClient';

interface PreviewPaneProps {
  productId: string;
  freePreviewPages: number;
}

/**
 * Renders the free-preview pages onto an HTML5 <canvas> — not an <img> — with
 * right-click, drag and text-select disabled. This is the same posture the Phase 5
 * DRM viewer will use for paid content, so this component is a deliberate rehearsal
 * for it (see phase-3 design doc, file plan).
 *
 * Canvas rather than <img> matters even here: an <img> exposes "Save image as...",
 * drag-to-desktop, and a real <img src> a user can copy straight out of devtools.
 * A canvas still isn't hack-proof (nothing client-side is), but it removes the
 * one-click affordances a "free preview" shouldn't hand out for free.
 */
export default function PreviewPane({ productId, freePreviewPages }: PreviewPaneProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [pageNumber, setPageNumber] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (freePreviewPages < 1) return;
    let objectUrl: string | null = null;
    let cancelled = false;

    setLoading(true);
    setError(false);

    restClient
      .get(`/products/${productId}/preview/${pageNumber}`, { responseType: 'blob' })
      .then((res) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(res.data);
        const img = new Image();
        img.onload = () => {
          if (cancelled || !canvasRef.current) return;
          const canvas = canvasRef.current;
          canvas.width = img.width;
          canvas.height = img.height;
          canvas.getContext('2d')?.drawImage(img, 0, 0);
          setLoading(false);
        };
        img.src = objectUrl;
      })
      .catch(() => {
        if (!cancelled) {
          setError(true);
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [productId, pageNumber, freePreviewPages]);

  if (freePreviewPages < 1) {
    return <p className="text-sm text-gray-400 italic">No free preview available for this product.</p>;
  }

  return (
    <div className="select-none">
      <div
        className="relative bg-gray-100 rounded-lg overflow-hidden border border-gray-200 flex items-center justify-center min-h-[400px] max-h-[70vh]"
        onContextMenu={(e) => e.preventDefault()}
      >
        {loading && (
          <svg className="animate-spin h-6 w-6 text-emerald-500 absolute" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
        )}
        {error ? (
          <p className="text-sm text-red-500 py-20">Couldn't load this preview page.</p>
        ) : (
          <canvas
            ref={canvasRef}
            draggable={false}
            className="max-w-full max-h-[70vh] w-auto h-auto object-contain"
            style={{ userSelect: 'none', WebkitUserSelect: 'none' }}
          />
        )}
      </div>

      <div className="flex items-center justify-center gap-3 mt-3">
        <button
          onClick={() => setPageNumber((p) => Math.max(1, p - 1))}
          disabled={pageNumber === 1}
          className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-600 disabled:opacity-40"
        >
          Previous
        </button>
        <span className="text-xs text-gray-500">
          Preview page {pageNumber} of {freePreviewPages}
        </span>
        <button
          onClick={() => setPageNumber((p) => Math.min(freePreviewPages, p + 1))}
          disabled={pageNumber >= freePreviewPages}
          className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-600 disabled:opacity-40"
        >
          Next
        </button>
      </div>
    </div>
  );
}
