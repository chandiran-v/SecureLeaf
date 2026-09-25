import { useEffect } from 'react';

/**
 * VIEW-07 (part 1) — stops the obvious keyboard shortcuts for turning the page into a file:
 * Ctrl/Cmd+P (print, which a browser will happily rasterize the canvas into a PDF for) and
 * Ctrl/Cmd+S (save page). The print *dialog* itself is also blocked at the CSS layer
 * (`@media print` in index.css) so a shortcut this hook somehow misses still prints a blank page.
 */
export function useBlockPrintAndSaveShortcuts(): void {
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const key = event.key.toLowerCase();
      if ((event.ctrlKey || event.metaKey) && (key === 'p' || key === 's')) {
        event.preventDefault();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);
}
