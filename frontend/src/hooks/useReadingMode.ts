import { useEffect } from 'react';

/** The class the print-blocking rule in index.css is scoped to (Phase 09C D5). */
export const READING_CLASS = 'sl-reading';

/**
 * Marks the document as "the secure reader is on screen" while the calling component is mounted.
 *
 * index.css blanks the printed page only under `body.sl-reading` (VIEW-07). Before Phase 09C that
 * rule applied to the WHOLE site, which also blocked buyers from printing receipts and legal pages.
 * Scoping it with a class keeps the reader unprintable and everything else printable. Cleanup runs
 * on unmount, so navigating from the reader to a receipt lifts the block.
 */
export function useReadingMode(): void {
  useEffect(() => {
    document.body.classList.add(READING_CLASS);
    return () => document.body.classList.remove(READING_CLASS);
  }, []);
}
