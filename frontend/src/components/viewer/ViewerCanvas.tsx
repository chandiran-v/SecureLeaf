import type { RefObject } from 'react';
import { useViewerStore } from '../../stores/viewerStore';

interface ViewerCanvasProps {
  canvasRef: RefObject<HTMLCanvasElement>;
  loading: boolean;
  printScreenBlanked: boolean;
  error?: boolean;
  onRetry?: () => void;
}

/**
 * D2 — the only element a page's pixels ever touch. No `<img>`, no CSS `background-image`, no
 * object URL: useSecureTile draws straight into this canvas from a decoded `ImageBitmap`.
 * VIEW-05/06 — select-none and draggable={false}/onDragStart prevented stop the two other
 * one-click ways to lift a page out of the DOM (text selection, drag-to-desktop).
 */
export default function ViewerCanvas({ canvasRef, loading, printScreenBlanked, error, onRetry }: ViewerCanvasProps) {
  const blurred = useViewerStore((state) => state.focusBlurred || state.devToolsBlurred);
  const hide = blurred || printScreenBlanked;

  return (
    <div className="relative flex flex-1 select-none items-center justify-center overflow-hidden bg-gray-950 p-4">
      <canvas
        ref={canvasRef}
        draggable={false}
        onDragStart={(event) => event.preventDefault()}
        className={`max-h-full max-w-full rounded shadow-2xl transition-[filter] duration-150 ${hide ? 'blur-3xl' : ''}`}
      />

      {hide && (
        <div className="absolute inset-0 flex items-center justify-center bg-gray-950/90 text-sm text-gray-300">
          {!printScreenBlanked && 'Reading paused'}
        </div>
      )}

      {loading && !hide && (
        <div className="absolute inset-0 flex items-center justify-center">
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
