import { describe, expect, it } from 'vitest';
import { resolveApiUrl } from './apiUrl';

describe('resolveApiUrl', () => {
  const tile = '/api/viewer/tiles/12/3?exp=1&sig=abc&v=DESKTOP';

  it('leaves the path alone for same-origin deployments (no or relative VITE_API_URL)', () => {
    expect(resolveApiUrl(tile, undefined)).toBe(tile);
    expect(resolveApiUrl(tile, '/api')).toBe(tile);
  });

  it('resolves against the backend origin when the API lives on another domain', () => {
    expect(resolveApiUrl(tile, 'https://secureleaf-backend.onrender.com/api')).toBe(
      'https://secureleaf-backend.onrender.com/api/viewer/tiles/12/3?exp=1&sig=abc&v=DESKTOP'
    );
  });

  it('never rewrites a URL that is already absolute', () => {
    const absolute = 'https://cdn.example.com/x.png';
    expect(resolveApiUrl(absolute, 'https://secureleaf-backend.onrender.com/api')).toBe(absolute);
  });
});
