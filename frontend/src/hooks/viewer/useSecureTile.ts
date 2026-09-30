import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { useApolloClient } from '@apollo/client';
import { VIEWER_PAGE_URL } from '../../graphql/queries/viewer.queries';
import { useAuthStore } from '../../store/authStore';
import { recoverFromUnauthorized } from '../../lib/session';
import {
  RateLimitedError,
  RequestBudget,
  ServerBusyError,
  parseRetryAfterSeconds,
  rateLimitedFromGraphQlError,
  retryOnServerBusy,
  retryOnceOnRateLimit,
} from '../../lib/rateLimit';
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
  /** True while waiting out a 429 (Phase 11, D7) — the viewer shows a subtle "Slow down…" hint. */
  slowDown: boolean;
  /** True while waiting out a 503 from the render pool (Phase 12, D4) — "Busy, retrying…". */
  busy: boolean;
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
  const [slowDown, setSlowDown] = useState(false);
  const [busy, setBusy] = useState(false);
  const retry = () => setRetryNonce((n) => n + 1);

  // Undrawn, prefetched bitmaps only — see the module comment above.
  const cacheRef = useRef(new Map<number, LoadedPage>());
  // Phase 11, D7 — recent tile requests, so prefetch can stand down near the server's budget.
  const budgetRef = useRef(new RequestBudget());
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
      budgetRef.current.record();
      let data: { viewerPageUrl: SignedPageUrl };
      try {
        ({ data } = await apolloClient.query<{ viewerPageUrl: SignedPageUrl }>({
          query: VIEWER_PAGE_URL,
          variables: { sessionToken: sessionTokenRef.current, pageNumber: page },
          fetchPolicy: 'network-only',
        }));
      } catch (err) {
        throw rateLimitedFromGraphQlError(err) ?? err;
      }
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

      if (response.status === 429) {
        throw new RateLimitedError(parseRetryAfterSeconds(response.headers.get('Retry-After')));
      }

      // Phase 12, D4 — 503: the server's render pool is full or slow (not our fault, unlike 429).
      if (response.status === 503) {
        throw new ServerBusyError(parseRetryAfterSeconds(response.headers.get('Retry-After')));
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
        // D7 — a 429 on the page the reader asked for: wait Retry-After, try once more, hint meanwhile.
        // Phase 12, D4 — a 503 is retried (jittered, at most twice) with a "Busy, retrying…" hint.
        const loaded = await retryOnceOnRateLimit(
          () =>
            retryOnServerBusy(
              () => loadPage(pageNumber),
              (active) => {
                if (!cancelled) setBusy(active);
              }
            ),
          (active) => {
            if (!cancelled) setSlowDown(active);
          }
        );
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
        if (hasNextPage && !cacheRef.current.has(pageNumber + 1) && budgetRef.current.canSpend()) {
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
        if (err instanceof RateLimitedError) {
          // Still limited after the one retry: an ordinary, retryable error (no 403/409/410 meaning).
          setError({ code: 'RATE_LIMITED', status: 429 });
        } else if (err instanceof ServerBusyError) {
          // Still busy after the retries: an ordinary, retryable error (the "Try again" button).
          setError({ code: 'SERVER_BUSY', status: 503 });
        } else if (err instanceof TileFetchError) {
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

  return { loading, error, retry, links, slowDown, busy };
}
