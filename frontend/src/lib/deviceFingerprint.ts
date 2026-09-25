/**
 * D3 — a stable-per-browser identifier sent with startViewerSession so the "opened on another
 * device" takeover panel can (eventually) distinguish devices. It is NOT a privacy-invasive
 * fingerprint (no canvas/font probing) — just a random id persisted in localStorage so the same
 * browser reuses the same value across tabs and sessions.
 */
const STORAGE_KEY = 'secureleaf-device-fingerprint';

export function getOrCreateDeviceFingerprint(): string {
  try {
    const existing = window.localStorage.getItem(STORAGE_KEY);
    if (existing) return existing;

    const generated =
      typeof crypto !== 'undefined' && 'randomUUID' in crypto
        ? crypto.randomUUID()
        : `fp-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    window.localStorage.setItem(STORAGE_KEY, generated);
    return generated;
  } catch {
    // Storage disabled (private browsing, etc.) — fall back to a per-call value; the only
    // consequence is that the takeover UX loses "same device" continuity, nothing breaks.
    return `fp-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
}
