import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useBlurOnFocusLoss } from './useBlurOnFocusLoss';
import { useViewerStore } from '../../stores/viewerStore';

function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
}

describe('useBlurOnFocusLoss', () => {
  beforeEach(() => {
    setVisibility('visible');
    useViewerStore.setState({ focusBlurred: false });
  });

  it('sets focusBlurred when the tab becomes hidden, and clears it when visible again', () => {
    renderHook(() => useBlurOnFocusLoss());

    act(() => {
      setVisibility('hidden');
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(useViewerStore.getState().focusBlurred).toBe(true);

    act(() => {
      setVisibility('visible');
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(useViewerStore.getState().focusBlurred).toBe(false);
  });

  it('sets focusBlurred on window blur even while the tab stays visible', () => {
    renderHook(() => useBlurOnFocusLoss());

    act(() => window.dispatchEvent(new Event('blur')));
    expect(useViewerStore.getState().focusBlurred).toBe(true);

    act(() => window.dispatchEvent(new Event('focus')));
    expect(useViewerStore.getState().focusBlurred).toBe(false);
  });
});
