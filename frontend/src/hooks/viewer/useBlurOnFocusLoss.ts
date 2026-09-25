import { useEffect } from 'react';
import { useViewerStore } from '../../stores/viewerStore';

/**
 * VIEW-09 — a page left visible in an unfocused window is an easy screenshot target. This
 * listens for two different ways a reader can stop "actively looking": the tab itself being
 * hidden (`visibilitychange`), and the whole browser window losing OS focus while the tab stays
 * visible (`window blur`, e.g. alt-tabbing to another app). Either one sets the shared
 * `focusBlurred` flag in the viewer store; ViewerCanvas is the thing that actually renders the
 * blur, so this hook has no visual output of its own.
 */
export function useBlurOnFocusLoss(): void {
  const setFocusBlurred = useViewerStore((state) => state.setFocusBlurred);

  useEffect(() => {
    const updateFromVisibility = () => setFocusBlurred(document.visibilityState === 'hidden');
    const onWindowBlur = () => setFocusBlurred(true);
    const onWindowFocus = () => setFocusBlurred(document.visibilityState === 'hidden');

    document.addEventListener('visibilitychange', updateFromVisibility);
    window.addEventListener('blur', onWindowBlur);
    window.addEventListener('focus', onWindowFocus);

    updateFromVisibility();

    return () => {
      document.removeEventListener('visibilitychange', updateFromVisibility);
      window.removeEventListener('blur', onWindowBlur);
      window.removeEventListener('focus', onWindowFocus);
    };
  }, [setFocusBlurred]);
}
