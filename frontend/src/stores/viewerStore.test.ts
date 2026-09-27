import { describe, it, expect, beforeEach } from 'vitest';
import { MAX_ZOOM, MIN_ZOOM, clampZoom, nextZoomStep, previousZoomStep, useViewerStore } from './viewerStore';

describe('viewer zoom', () => {
  beforeEach(() => {
    useViewerStore.getState().reset(1);
  });

  it('starts at fit (1) and steps through round zoom levels', () => {
    const store = useViewerStore.getState();
    expect(useViewerStore.getState().zoom).toBe(1);
    store.zoomIn();
    expect(useViewerStore.getState().zoom).toBe(1.25);
    store.zoomIn();
    expect(useViewerStore.getState().zoom).toBe(1.5);
    store.zoomOut();
    store.zoomOut();
    store.zoomOut();
    expect(useViewerStore.getState().zoom).toBe(0.75);
  });

  it('never goes past the minimum or maximum', () => {
    const store = useViewerStore.getState();
    for (let i = 0; i < 20; i++) store.zoomIn();
    expect(useViewerStore.getState().zoom).toBe(MAX_ZOOM);
    for (let i = 0; i < 20; i++) store.zoomOut();
    expect(useViewerStore.getState().zoom).toBe(MIN_ZOOM);
  });

  it('snaps to the next round step from an in-between value (e.g. after a pinch)', () => {
    expect(nextZoomStep(1.1)).toBe(1.25);
    expect(previousZoomStep(1.1)).toBe(1);
    expect(nextZoomStep(MAX_ZOOM)).toBe(MAX_ZOOM);
    expect(previousZoomStep(MIN_ZOOM)).toBe(MIN_ZOOM);
  });

  it('clamps free-form zoom values and ignores nonsense', () => {
    expect(clampZoom(10)).toBe(MAX_ZOOM);
    expect(clampZoom(0.01)).toBe(MIN_ZOOM);
    expect(clampZoom(Number.NaN)).toBe(1);
    useViewerStore.getState().setZoom(1.37);
    expect(useViewerStore.getState().zoom).toBe(1.37);
  });

  it('resets zoom to fit when a (new) book is opened', () => {
    useViewerStore.getState().setZoom(2);
    useViewerStore.getState().reset(3);
    expect(useViewerStore.getState().zoom).toBe(1);
    expect(useViewerStore.getState().currentPage).toBe(3);
  });
});
