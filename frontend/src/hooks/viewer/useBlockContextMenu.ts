import { useCallback } from 'react';

/**
 * VIEW-04 — the right-click menu offers "Save image as…" and "Inspect" for free, so it's the
 * first thing to remove inside the viewer. Returns a plain event handler rather than attaching
 * a listener itself, so callers wire it to exactly the element they mean (the viewer root),
 * not the whole document.
 */
export function useBlockContextMenu() {
  return useCallback((event: { preventDefault: () => void }) => {
    event.preventDefault();
  }, []);
}
