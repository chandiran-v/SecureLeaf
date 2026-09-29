import { renderHook } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { READING_CLASS, useReadingMode } from './useReadingMode';

// Read the real stylesheet from disk: vitest doesn't process CSS, so `?raw` would come back empty.
const indexCss = readFileSync('src/index.css', 'utf8')   // vitest runs from the frontend/ folder;

describe('print rule scoping (Phase 09C D5, VIEW-07 regression)', () => {
  it('the print-blocking rule is scoped to body.sl-reading, never bare `body`', () => {
    const printBlock = indexCss.slice(indexCss.indexOf('@media print'));
    expect(printBlock).toMatch(/body\.sl-reading\s*\{[^}]*display:\s*none/);
    expect(printBlock).not.toMatch(/(^|[\s,{}])body\s*\{/);
  });

  it('marks the body while the reader is mounted and clears it on unmount', () => {
    expect(document.body).not.toHaveClass(READING_CLASS);   // no reader → the receipt is printable
    const { unmount } = renderHook(() => useReadingMode());
    expect(document.body).toHaveClass(READING_CLASS);        // reader mounted → print blanks the page
    unmount();
    expect(document.body).not.toHaveClass(READING_CLASS);
  });
});
