import { useCallback, useEffect, useState } from 'react';
import { isAxiosError } from 'axios';
import restClient from '../lib/restClient';

export interface UseCreatorPageResult {
  /** An object URL for the page image, or null while loading / on error. */
  imageUrl: string | null;
  loading: boolean;
  /** 'RATE_LIMITED' for a 429, 'NOT_FOUND' for a 404, 'FAILED' for anything else. */
  error: 'RATE_LIMITED' | 'NOT_FOUND' | 'FAILED' | null;
  retry: () => void;
}

/**
 * Owner preview — one clean (unwatermarked) page of the creator's OWN product.
 *
 * Fetched through restClient (not `<img src>`) because the endpoint needs the Bearer token, which an
 * `<img>` tag can't send. The bytes become an object URL, revoked as soon as the page changes or the
 * component unmounts so old pages don't pile up in memory.
 *
 * Deliberately NOT the buyer's useSecureTile: no viewer session, no signed URL, no canvas-only rule.
 * Those exist to slow a buyer down from copying content they don't own; the creator owns this one.
 */
export function useCreatorPage(productId: string, pageNumber: number): UseCreatorPageResult {
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<UseCreatorPageResult['error']>(null);
  const [retryNonce, setRetryNonce] = useState(0);
  const retry = useCallback(() => setRetryNonce((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;
    setLoading(true);
    setError(null);

    restClient
      .get<Blob>(`/creator/products/${productId}/pages/${pageNumber}`, { responseType: 'blob' })
      .then((res) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(res.data);
        setImageUrl(objectUrl);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const status = isAxiosError(err) ? err.response?.status : undefined;
        setError(status === 429 ? 'RATE_LIMITED' : status === 404 ? 'NOT_FOUND' : 'FAILED');
        setImageUrl(null);
        setLoading(false);
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [productId, pageNumber, retryNonce]);

  return { imageUrl, loading, error, retry };
}
