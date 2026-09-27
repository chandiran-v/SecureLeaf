import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { useApolloClient } from '@apollo/client';
import { VIEWER_PAGE_URL } from '../../graphql/queries/viewer.queries';
import { useAuthStore } from '../../store/authStore';
import { recoverFromUnauthorized } from '../../lib/session';
import type { PageLink, SignedPageUrl, ViewerSession } from '../../types';

/** Most bytes a browser can decode this way, kept small for smooth page turns. */
const MAX_CACHED_BITMAPS = 3;

/** A decoded page and its clickable links (V8), kept together so a prefetched page has both. */
interface LoadedPage {
  bitmap: ImageBitmap;
  links: PageLink[];
}

class TileFetchError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string | null
  ) {
    super(`Tile fetch failed with status ${status}`);
  }
}

export interface UseSecureTileOptions {
  /** Pass null while the session isn't ACTIVE — no fetch happens without one. */
  session: ViewerSession | null;
  pageNumber: number;
  pageCount: number | null;
  canvasRef: RefObject<HTMLCanvasElement>;
  /** A 409 from the tile endpoint: another device just took over. */
  onSuperseded: () => void;
  /** A 410 from the tile endpoint: the lease lapsed — restart (D4 says silently, once). */
  onExpired: () => void;
}

export interface UseSecureTileResult {
  loading: boolean;
  error: { code: string | null; status: number | null } | null;
  /** Re-runs the fetch for the current page — for a generic failure (a blip, not 403/409/410). */
  retry: () => void;
  /** The drawn page's clickable links (V8). Empty while a page is loading. */
  links: PageLink[];
}

function drawBitmapToCanvas(canvas: HTMLCanvasElement, bitmap: ImageBitmap): void {
  const dpr = window.devicePixelRatio || 1;
  canvas.width = bitmap.width * dpr;
  canvas.height = bitmap.height * dpr;
  canvas.style.aspectRatio = `${bitmap.width} / ${bitmap.height}`;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, bitmap.width, bitmap.height);
  ctx.drawImage(bitmap, 0, 0, bitmap.width, bitmap.height);
}

function evictOldest(cache: Map<number, LoadedPage>, maxSize: number): void {
  while (cache.size > maxSize) {
    const oldestKey = cache.keys().next().value;
    if (oldestKey === undefined) break;
    cache.get(oldestKey)?.bitmap.close();
    cache.delete(oldestKey);
  }
}

/**
 * D2/D5 — the one place that ever touches a page's pixels. A page is fetched with the
 * `Authorization` header (never an `<img src>`, which can't carry one), decoded off-DOM with
 * `createImageBitmap`, drawn straight to the canvas, and closed the moment it's no longer
 * needed. Signed URLs are single-use and expire in ~30s, so they are never cached — only the
 * *decoded* bitmap of a page fetched ahead of time (the prefetch of page n+1) is kept, capped at
 * `MAX_CACHED_BITMAPS` undrawn bitmaps so memory can't grow unbounded from fast page-turning.
 */
export function useSecureTile({
  session,
  pageNumber,
  pageCount,
  canvasRef,
  onSuperseded,
  onExpired,
}: UseSecureTileOptions): UseSecureTileResult {
  const apolloClient = useApolloClient();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<{ code: string | null; status: number | null } | null>(null);
  const [retryNonce, setRetryNonce] = useState(0);
  const [links, setLinks] = useState<PageLink[]>([]);
  const retry = () => setRetryNonce((n) => n + 1);

  // Undrawn, prefetched bitmaps only — see the module comment above.
  const cacheRef = useRef(new Map<number, LoadedPage>());
  const sessionTokenRef = useRef<string | null>(null);
  sessionTokenRef.current = session?.sessionToken ?? null;

  useEffect(() => {
    const cache = cacheRef.current;
    return () => {
      for (const page of cache.values()) page.bitmap.close();
      cache.clear();
    };
  }, []);

  useEffect(() => {
    if (!session) return undefined;
    let cancelled = false;

    async function fetchAndDecode(page: number): Promise<LoadedPage> {
      const { data } = await apolloClient.query<{ viewerPageUrl: SignedPageUrl }>({
        query: VIEWER_PAGE_URL,
        variables: { sessionToken: sessionTokenRef.current, pageNumber: page },
        fetchPolicy: 'network-only',
      });
      const url = data.viewerPageUrl.url;
      const fetchTile = () => {
        const accessToken = useAuthStore.getState().accessToken;
        return fetch(url, {
          cache: 'no-store',
          headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
        });
      };

      let response = await fetchTile();
      // The login expired between signing the URL and fetching it. The JWT filter rejects the
      // request before the single-use check runs, so the same signed URL is still good:
      // refresh once and retry it. If the refresh fails, the session-expired modal takes over.
      if (response.status === 401) {
        let code: string | null = null;
        try {
          code = ((await response.json()) as { code?: string })?.code ?? null;
        } catch {
          // no JSON body
        }
        if (await recoverFromUnauthorized(code)) response = await fetchTile();
      }

      if (!response.ok) {
        let code: string | null = null;
        try {
          const body = (await response.json()) as { code?: string };
          code = body?.code ?? null;
        } catch {
          // Non-JSON error body — status code alone still tells the caller what happened.
        }
        throw new TileFetchError(response.status, code);
      }

      const blob = await response.blob();
      return { bitmap: await createImageBitmap(blob), links: data.viewerPageUrl.links ?? [] };
    }

    async function loadPage(page: number): Promise<LoadedPage> {
      const cached = cacheRef.current.get(page);
      if (cached) {
        cacheRef.current.delete(page);
        return cached;
      }
      return fetchAndDecode(page);
    }

    async function run() {
      setLoading(true);
      setError(null);
      setLinks([]); // never leave the previous page's links over the next page
      try {
        const loaded = await loadPage(pageNumber);
        if (cancelled) {
          loaded.bitmap.close();
          return;
        }
        if (canvasRef.current) drawBitmapToCanvas(canvasRef.current, loaded.bitmap);
        loaded.bitmap.close();
        setLinks(loaded.links);
        setLoading(false);

        // D5 — prefetch the next page into the cache; failures here are silent, the real
        // fetch on navigation will simply try again.
        const hasNextPage = pageCount == null || pageNumber < pageCount;
        if (hasNextPage && !cacheRef.current.has(pageNumber + 1)) {
          fetchAndDecode(pageNumber + 1)
            .then((prefetched) => {
              if (cancelled) {
                prefetched.bitmap.close();
                return;
              }
              cacheRef.current.set(pageNumber + 1, prefetched);
              evictOldest(cacheRef.current, MAX_CACHED_BITMAPS);
            })
            .catch(() => undefined);
        }
      } catch (err) {
        if (cancelled) return;
        if (err instanceof TileFetchError) {
          if (err.status === 409) {
            onSuperseded();
            return;
          }
          if (err.status === 410) {
            onExpired();
            return;
          }
          setError({ code: err.code, status: err.status });
        } else {
          setError({ code: null, status: null });
        }
        setLoading(false);
      }
    }

    void run();
    return () => {
      cancelled = true;
    };
  }, [session, pageNumber, pageCount, canvasRef, onSuperseded, onExpired, apolloClient, retryNonce]);

  return { loading, error, retry, links };
}
