import { useEffect } from 'react';
import { useViewerStore } from '../../stores/viewerStore';

/**
 * Reader zoom from the keyboard: `+`/`=` in, `-` out, `0` back to fit — with or without
 * Ctrl/Cmd. With Ctrl/Cmd these are also the browser's own page-zoom shortcuts, which we
 * deliberately swallow inside the reader:
 *  - browser zoom scales the whole UI but the canvas just re-fits the new viewport, so the page
 *    never actually gets bigger (the bug this feature fixes);
 *  - browser zoom changes `innerWidth` relative to `outerWidth`, which can trip the VIEW-08
 *    DevTools heuristic and blur the page for no reason.
 * Ctrl + mouse wheel / trackpad pinch is handled the same way in ViewerCanvas.
 */
export function useZoomShortcuts(): void {
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.altKey) return;
      const { zoomIn, zoomOut, resetZoom } = useViewerStore.getState();
      let action: (() => void) | null = null;
      if (event.key === '+' || event.key === '=') action = zoomIn;
      else if (event.key === '-' || event.key === '_') action = zoomOut;
      else if (event.key === '0') action = resetZoom;
      if (!action) return;
      event.preventDefault();
      action();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);
}
