import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDevToolsHeuristic } from './useDevToolsHeuristic';
import { useViewerStore } from '../../stores/viewerStore';

function setDimensions(outerWidth: number, innerWidth: number, outerHeight = 800, innerHeight = 800) {
  Object.defineProperty(window, 'outerWidth', { value: outerWidth, configurable: true });
  Object.defineProperty(window, 'innerWidth', { value: innerWidth, configurable: true });
  Object.defineProperty(window, 'outerHeight', { value: outerHeight, configurable: true });
  Object.defineProperty(window, 'innerHeight', { value: innerHeight, configurable: true });
}

describe('useDevToolsHeuristic', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    setDimensions(1024, 1024);
    useViewerStore.setState({ devToolsBlurred: false });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('flags devToolsBlurred when the outer/inner width gap exceeds the threshold', () => {
    setDimensions(1024, 800); // 224px gap > 160px threshold
    renderHook(() => useDevToolsHeuristic());
    expect(useViewerStore.getState().devToolsBlurred).toBe(true);
  });

  it('does not flag a small, normal browser-chrome gap', () => {
    setDimensions(1024, 1000); // 24px gap
    renderHook(() => useDevToolsHeuristic());
    expect(useViewerStore.getState().devToolsBlurred).toBe(false);
  });

  it('re-checks on the 1s poll interval, not just on mount', () => {
    renderHook(() => useDevToolsHeuristic());
    expect(useViewerStore.getState().devToolsBlurred).toBe(false);

    setDimensions(1024, 800);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(useViewerStore.getState().devToolsBlurred).toBe(true);
  });
});
