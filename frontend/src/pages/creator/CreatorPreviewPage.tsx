import { useCallback, useEffect } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@apollo/client';
import { MY_PRODUCTS } from '../../graphql/queries/product.queries';
import { useCreatorPage } from '../../hooks/useCreatorPage';
import type { Product } from '../../types';

const buttonClass =
  'rounded-lg px-3 py-1.5 text-sm font-medium text-gray-200 transition-colors hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-30';

const ERROR_TEXT = {
  RATE_LIMITED: 'You are turning pages too fast. Wait a moment and try again.',
  NOT_FOUND: 'This page is not available. The document may still be processing.',
  FAILED: 'Could not load this page.',
} as const;

/**
 * /creator/products/:id/view — the owner preview: the creator reads their own product exactly as
 * uploaded, with no watermark. Product title and page count come from the dashboard's MY_PRODUCTS
 * query (usually already cached, so no extra round trip); the server re-checks ownership on every
 * page anyway, so a hand-typed URL for someone else's product just 404s.
 */
export default function CreatorPreviewPage() {
  const { id: productId = '' } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();

  const { data, loading: productLoading } = useQuery<{ myProducts: Product[] }>(MY_PRODUCTS, {
    fetchPolicy: 'cache-first',
  });
  const product = data?.myProducts.find((p) => p.id === productId);
  const pageCount = product?.pageCount ?? null;

  const requested = Math.floor(Number(searchParams.get('page')) || 1);
  const page = Math.max(1, pageCount != null ? Math.min(requested, pageCount) : requested);

  const goToPage = useCallback(
    (next: number) => {
      const clamped = Math.max(1, pageCount != null ? Math.min(next, pageCount) : next);
      setSearchParams({ page: String(clamped) }, { replace: true });
    },
    [pageCount, setSearchParams]
  );

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'ArrowRight') goToPage(page + 1);
      else if (event.key === 'ArrowLeft') goToPage(page - 1);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [page, goToPage]);

  const { imageUrl, loading, error, retry } = useCreatorPage(productId, page);

  return (
    <div className="fixed inset-0 z-40 flex flex-col bg-gray-950 text-white">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-white/10 bg-white/5 px-4 py-3 backdrop-blur-sm sm:px-6">
        <div className="flex min-w-0 items-center gap-4">
          <Link to="/creator" className="text-sm font-medium text-gray-300 transition-colors hover:text-white">
            ← Dashboard
          </Link>
          <p className="truncate text-sm font-semibold text-white">
            {product?.title ?? (productLoading ? '' : 'Product')}
          </p>
          <span className="hidden whitespace-nowrap rounded-full border border-emerald-400/30 bg-emerald-400/10 px-2.5 py-0.5 text-[11px] font-medium text-emerald-300 sm:inline">
            Owner preview · no watermark
          </span>
        </div>

        <div className="flex items-center gap-3">
          <button onClick={() => goToPage(page - 1)} disabled={page <= 1} aria-label="Previous page" className={buttonClass}>
            ← Prev
          </button>
          <span className="min-w-[6rem] text-center text-sm text-gray-300">
            {pageCount != null ? `Page ${page} / ${pageCount}` : `Page ${page}`}
          </span>
          <button
            onClick={() => goToPage(page + 1)}
            disabled={pageCount != null && page >= pageCount}
            aria-label="Next page"
            className={buttonClass}
          >
            Next →
          </button>
        </div>
      </div>

      <div className="flex flex-1 items-start justify-center overflow-auto p-4 sm:p-8">
        {error ? (
          <div className="mt-20 text-center">
            <p className="mb-4 text-sm text-gray-300">{ERROR_TEXT[error]}</p>
            <button onClick={retry} className="rounded-lg bg-white/10 px-4 py-2 text-sm font-medium hover:bg-white/20">
              Try again
            </button>
          </div>
        ) : loading || !imageUrl ? (
          <div className="mt-20 h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-white" role="status" aria-label="Loading page" />
        ) : (
          <img
            src={imageUrl}
            alt={`Page ${page}${product ? ` of ${product.title}` : ''}`}
            className="h-auto max-w-full rounded shadow-2xl lg:max-w-4xl"
          />
        )}
      </div>
    </div>
  );
}
