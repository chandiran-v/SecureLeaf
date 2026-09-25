import { useEffect, useState } from 'react';

/**
 * VIEW-07 (part 2) — best effort, and the learning note says so plainly: there is no web API
 * that lets a page find out a screenshot happened, or stop one. All this can do is notice the
 * PrintScreen *key itself* being pressed (which only covers "whole screen to clipboard" on
 * Windows, not the Snipping Tool, not a phone camera, not any of it) and briefly blank the
 * canvas so that one specific capture method gets nothing useful. Everything else — the OS
 * screenshot shortcut on Mac, any third-party capture tool — is untouched by this.
 */
const BLANK_DURATION_MS = 400;

export function usePrintScreenBlank(): boolean {
  const [blanked, setBlanked] = useState(false);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null;

    const handler = (event: KeyboardEvent) => {
      if (event.key !== 'PrintScreen') return;
      setBlanked(true);
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => setBlanked(false), BLANK_DURATION_MS);
    };

    window.addEventListener('keyup', handler);
    return () => {
      window.removeEventListener('keyup', handler);
      if (timer) clearTimeout(timer);
    };
  }, []);

  return blanked;
}
