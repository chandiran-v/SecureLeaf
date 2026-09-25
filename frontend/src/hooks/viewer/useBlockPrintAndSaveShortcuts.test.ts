import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useBlockPrintAndSaveShortcuts } from './useBlockPrintAndSaveShortcuts';

function dispatchKeydown(key: string, opts: { ctrlKey?: boolean; metaKey?: boolean } = {}) {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...opts });
  window.dispatchEvent(event);
  return event;
}

describe('useBlockPrintAndSaveShortcuts', () => {
  it('prevents Ctrl+P', () => {
    renderHook(() => useBlockPrintAndSaveShortcuts());
    const event = dispatchKeydown('p', { ctrlKey: true });
    expect(event.defaultPrevented).toBe(true);
  });

  it('prevents Cmd+S', () => {
    renderHook(() => useBlockPrintAndSaveShortcuts());
    const event = dispatchKeydown('s', { metaKey: true });
    expect(event.defaultPrevented).toBe(true);
  });

  it('leaves an unrelated key alone', () => {
    renderHook(() => useBlockPrintAndSaveShortcuts());
    const event = dispatchKeydown('a', { ctrlKey: true });
    expect(event.defaultPrevented).toBe(false);
  });
});
