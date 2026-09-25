import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePrintScreenBlank } from './usePrintScreenBlank';

describe('usePrintScreenBlank', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('blanks briefly after PrintScreen, then clears itself', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => usePrintScreenBlank());
    expect(result.current).toBe(false);

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keyup', { key: 'PrintScreen' }));
    });
    expect(result.current).toBe(true);

    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(result.current).toBe(false);
  });

  it('ignores unrelated keys', () => {
    const { result } = renderHook(() => usePrintScreenBlank());
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keyup', { key: 'a' }));
    });
    expect(result.current).toBe(false);
  });
});
