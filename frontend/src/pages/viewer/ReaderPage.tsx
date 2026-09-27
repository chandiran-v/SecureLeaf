import { useCallback, useEffect, useRef } from 'react';
import { Navigate, useParams, useSearchParams } from 'react-router-dom';
import { useViewerSession } from '../../hooks/viewer/useViewerSession';
import { useSecureTile } from '../../hooks/viewer/useSecureTile';
import { useBlockContextMenu } from '../../hooks/viewer/useBlockContextMenu';
import { useBlurOnFocusLoss } from '../../hooks/viewer/useBlurOnFocusLoss';
import { useDevToolsHeuristic } from '../../hooks/viewer/useDevToolsHeuristic';
import { useBlockPrintAndSaveShortcuts } from '../../hooks/viewer/useBlockPrintAndSaveShortcuts';
import { usePrintScreenBlank } from '../../hooks/viewer/usePrintScreenBlank';
import { useZoomShortcuts } from '../../hooks/viewer/useZoomShortcuts';
import { useViewerStore } from '../../stores/viewerStore';
import ReaderSkeleton from '../../components/viewer/ReaderSkeleton';
import NotEntitledPanel from '../../components/viewer/NotEntitledPanel';
import SessionEndedPanel from '../../components/viewer/SessionEndedPanel';
import ViewerToolbar from '../../components/viewer/ViewerToolbar';
import ViewerCanvas from '../../components/viewer/ViewerCanvas';

function clampPage(page: number, pageCount: number | null): number {
  const safePage = Number.isFinite(page) && page >= 1 ? Math.floor(page) : 1;
  return pageCount != null ? Math.min(safePage, Math.max(1, pageCount)) : safePage;
}

/**
 * D1 — `/read/:productId`, full-screen, no AppLayout chrome. Orchestrates the session lifecycle
 * (useViewerSession), one page's bytes (useSecureTile), page navigation (D6), zoom
 * (useZoomShortcuts + ViewerCanvas), and every piracy-friction control (D7). See docs/phases/phase-05b-secure-viewer-frontend.md for the decisions
 * referenced by number throughout this file and its hooks/components.
 */
export default function ReaderPage() {
  const { productId } = useParams<{ productId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const { status, session, restart, notifySuperseded } = useViewerSession(productId ?? '');
  const pageCount = session?.pageCount ?? null;

  const currentPage = useViewerStore((state) => state.currentPage);
  const setCurrentPage = useViewerStore((state) => state.setCurrentPage);
  const resetViewerUi = useViewerStore((state) => state.reset);

  // Reset local viewer UI state once per product, seeded from ?page= (D6 — a reload resumes).
  useEffect(() => {
    const requestedPage = Number(searchParams.get('page')) || 1;
    resetViewerUi(requestedPage);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productId]);

  // pageCount isn't known until the session starts, so `currentPage` itself may briefly hold an
  // out-of-range value (e.g. a stale ?page= from a shorter previous edition). Every consumer
  // reads this derived, always-in-range value instead of the raw store field — that way a fetch
  // for an out-of-range page number never fires, not even for one render.
  const clampedPage = clampPage(currentPage, pageCount);

  const goToPage = useCallback(
    (page: number) => {
      const clamped = clampPage(page, pageCount);
      setSearchParams({ page: String(clamped) }, { replace: true });
      setCurrentPage(clamped);
    },
    [pageCount, setSearchParams, setCurrentPage]
  );

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'ArrowRight') goToPage(clampedPage + 1);
      else if (event.key === 'ArrowLeft') goToPage(clampedPage - 1);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [clampedPage, goToPage]);

  const blockContextMenu = useBlockContextMenu();
  useBlurOnFocusLoss();
  useDevToolsHeuristic();
  useBlockPrintAndSaveShortcuts();
  useZoomShortcuts();
  const printScreenBlanked = usePrintScreenBlank();

  const {
    loading: tileLoading,
    error: tileError,
    retry: retryTile,
  } = useSecureTile({
    session: status === 'active' ? session : null,
    pageNumber: clampedPage,
    pageCount,
    canvasRef,
    onSuperseded: notifySuperseded,
    onExpired: restart,
  });

  if (!productId) {
    return <Navigate to="/library" replace />;
  }

  if (status === 'starting') {
    return <ReaderSkeleton />;
  }

  if (status === 'error') {
    return <NotEntitledPanel productId={productId} />;
  }

  return (
    <div
      className="fixed inset-0 z-40 flex select-none flex-col bg-gray-950 text-white"
      onContextMenu={blockContextMenu}
      onDragStart={(event) => event.preventDefault()}
    >
      <ViewerToolbar
        productId={productId}
        currentPage={clampedPage}
        pageCount={pageCount}
        onPrev={() => goToPage(clampedPage - 1)}
        onNext={() => goToPage(clampedPage + 1)}
      />

      <ViewerCanvas
        canvasRef={canvasRef}
        loading={tileLoading}
        printScreenBlanked={printScreenBlanked}
        error={!!tileError}
        onRetry={retryTile}
      />

      {(status === 'superseded' || status === 'expired') && (
        <SessionEndedPanel variant={status} onContinue={restart} />
      )}
    </div>
  );
}
