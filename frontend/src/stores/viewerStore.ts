/**
 * Local, client-only UI state for the secure reader (D8): current page and the two independent
 * "blur the page" reasons. Remote data (the viewer session itself, signed tile URLs) goes
 * through Apollo instead — see useViewerSession/useSecureTile. Splitting it this way means a
 * component like ViewerCanvas can read "am I blurred right now" without needing props threaded
 * down from ReaderPage, and without re-fetching anything from the server.
 */
import { create } from 'zustand';

interface ViewerUiState {
  currentPage: number;
  /** VIEW-09 — tab hidden or window lost focus. */
  focusBlurred: boolean;
  /** VIEW-08 — the DevTools-size heuristic tripped. */
  devToolsBlurred: boolean;
  setCurrentPage: (page: number) => void;
  setFocusBlurred: (blurred: boolean) => void;
  setDevToolsBlurred: (blurred: boolean) => void;
  /** Called when ReaderPage mounts for a (possibly new) product, so stale state from a
   *  previously-viewed book never leaks into the next one. */
  reset: (initialPage: number) => void;
}

export const useViewerStore = create<ViewerUiState>((set) => ({
  currentPage: 1,
  focusBlurred: false,
  devToolsBlurred: false,
  setCurrentPage: (page) => set({ currentPage: page }),
  setFocusBlurred: (blurred) => set({ focusBlurred: blurred }),
  setDevToolsBlurred: (blurred) => set({ devToolsBlurred: blurred }),
  reset: (initialPage) => set({ currentPage: initialPage, focusBlurred: false, devToolsBlurred: false }),
}));
