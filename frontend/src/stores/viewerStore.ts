/**
 * Local, client-only UI state for the secure reader (D8): current page, zoom, and the two
 * independent "blur the page" reasons. Remote data (the viewer session itself, signed tile URLs)
 * goes through Apollo instead — see useViewerSession/useSecureTile. Splitting it this way means a
 * component like ViewerCanvas can read "am I blurred right now" without needing props threaded
 * down from ReaderPage, and without re-fetching anything from the server.
 */
import { create } from 'zustand';

/** Zoom is a multiplier of "fit the whole page on screen" (1 = fit), not of the image's pixels. */
export const MIN_ZOOM = 0.5;
export const MAX_ZOOM = 3;
/** Button and keyboard zoom snap to these, so repeated clicks land on round, familiar numbers. */
export const ZOOM_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2, 2.5, 3] as const;

export function clampZoom(zoom: number): number {
  if (!Number.isFinite(zoom)) return 1;
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom));
}

/** Next step above the current zoom. Works from off-step values too (e.g. after a pinch). */
export function nextZoomStep(zoom: number): number {
  return ZOOM_STEPS.find((step) => step > zoom + 1e-6) ?? MAX_ZOOM;
}

/** Next step below the current zoom. */
export function previousZoomStep(zoom: number): number {
  return [...ZOOM_STEPS].reverse().find((step) => step < zoom - 1e-6) ?? MIN_ZOOM;
}

interface ViewerUiState {
  currentPage: number;
  zoom: number;
  /** VIEW-09 — tab hidden or window lost focus. */
  focusBlurred: boolean;
  /** VIEW-08 — the DevTools-size heuristic tripped. */
  devToolsBlurred: boolean;
  setCurrentPage: (page: number) => void;
  setZoom: (zoom: number) => void;
  zoomIn: () => void;
  zoomOut: () => void;
  resetZoom: () => void;
  setFocusBlurred: (blurred: boolean) => void;
  setDevToolsBlurred: (blurred: boolean) => void;
  /** Called when ReaderPage mounts for a (possibly new) product, so stale state from a
   *  previously-viewed book never leaks into the next one. */
  reset: (initialPage: number) => void;
}

export const useViewerStore = create<ViewerUiState>((set) => ({
  currentPage: 1,
  zoom: 1,
  focusBlurred: false,
  devToolsBlurred: false,
  setCurrentPage: (page) => set({ currentPage: page }),
  setZoom: (zoom) => set({ zoom: clampZoom(zoom) }),
  zoomIn: () => set((state) => ({ zoom: nextZoomStep(state.zoom) })),
  zoomOut: () => set((state) => ({ zoom: previousZoomStep(state.zoom) })),
  resetZoom: () => set({ zoom: 1 }),
  setFocusBlurred: (blurred) => set({ focusBlurred: blurred }),
  setDevToolsBlurred: (blurred) => set({ devToolsBlurred: blurred }),
  reset: (initialPage) => set({ currentPage: initialPage, zoom: 1, focusBlurred: false, devToolsBlurred: false }),
}));
