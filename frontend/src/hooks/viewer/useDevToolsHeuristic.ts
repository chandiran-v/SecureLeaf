import { useEffect } from 'react';
import { useViewerStore } from '../../stores/viewerStore';

/**
 * VIEW-08 — a HEURISTIC, not a detector. Open DevTools (docked to a side of the window) steals
 * viewport space without shrinking the outer window, so the gap between `outerWidth`/`Height`
 * and `innerWidth`/`Height` grows past what a normal browser chrome accounts for. Undocked
 * DevTools (a separate OS window) don't touch these numbers at all and evade this completely;
 * some browser zoom levels can also produce a false positive. That's exactly why this only
 * blurs the page (same visible effect as VIEW-09) instead of ending the session outright — an
 * honest heuristic should never be trusted with an irreversible action.
 */
const THRESHOLD_PX = 160;
const POLL_INTERVAL_MS = 1000;

function devToolsLikelyOpen(): boolean {
  return window.outerWidth - window.innerWidth > THRESHOLD_PX || window.outerHeight - window.innerHeight > THRESHOLD_PX;
}

export function useDevToolsHeuristic(): void {
  const setDevToolsBlurred = useViewerStore((state) => state.setDevToolsBlurred);

  useEffect(() => {
    const check = () => setDevToolsBlurred(devToolsLikelyOpen());

    check();
    window.addEventListener('resize', check);
    const intervalId = window.setInterval(check, POLL_INTERVAL_MS);

    return () => {
      window.removeEventListener('resize', check);
      window.clearInterval(intervalId);
    };
  }, [setDevToolsBlurred]);
}
