import { useQuery } from '@apollo/client';
import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PRODUCTS } from '../graphql/queries/marketplace.queries';
import { useDebouncedValue } from './useDebouncedValue';
import type { Product, ProductFilterInput, ProductSortBy } from '../types';

const PAGE_SIZE = 20;
const SEARCH_DEBOUNCE_MS = 350;

/**
 * Single source of truth for marketplace browsing state: the URL's query string
 * (D6). This makes the page shareable, back-button-safe, and lines up with how
 * Apollo's cache key already varies by query variables — pasting the same URL
 * into a new tab reproduces the same query variables and (once cached) the same
 * result.
 *
 * The raw `q` param updates on every keystroke (so the search box stays responsive),
 * but the value actually sent to Apollo is debounced 350ms — typing doesn't spam the
 * network or, via `replace: true` below, the browser history.
 */
export function useProductSearch() {
  const [searchParams, setSearchParams] = useSearchParams();

  const rawSearchQuery = searchParams.get('q') ?? '';
  const debouncedSearchQuery = useDebouncedValue(rawSearchQuery, SEARCH_DEBOUNCE_MS);

  const category = searchParams.get('category') ?? undefined;
  const sort = (searchParams.get('sort') as ProductSortBy | null) ?? undefined;
  const page = Number(searchParams.get('page') ?? '0') || 0;
  const minPrice = searchParams.get('minPrice');
  const maxPrice = searchParams.get('maxPrice');
  const free = searchParams.get('free') === 'true';
  const minRatingParam = searchParams.get('minRating');
  const minRating = minRatingParam ? Number(minRatingParam) : undefined;

  const filter: ProductFilterInput = useMemo(
    () => ({
      searchQuery: debouncedSearchQuery || undefined,
      categorySlug: category,
      sortBy: sort,
      minPricePaise: minPrice ? Number(minPrice) : undefined,
      maxPricePaise: maxPrice ? Number(maxPrice) : undefined,
      isFree: free || undefined,
      minRating,
    }),
    [debouncedSearchQuery, category, sort, minPrice, maxPrice, free, minRating]
  );

  const { data, loading, error, refetch } = useQuery<{
    products: { content: Product[]; totalElements: number; totalPages: number; pageNumber: number };
  }>(PRODUCTS, {
    variables: { filter, page, size: PAGE_SIZE },
    fetchPolicy: 'cache-and-network',
  });

  /** Merges a partial change into the URL, always writing with replace so typing
   *  doesn't spam browser history (D6). Any filter change other than pagination
   *  itself resets the page back to 0. */
  function updateParams(patch: Record<string, string | undefined>, resetPage = true) {
    const next = new URLSearchParams(searchParams);
    Object.entries(patch).forEach(([key, value]) => {
      if (value === undefined || value === '') {
        next.delete(key);
      } else {
        next.set(key, value);
      }
    });
    if (resetPage) next.delete('page');
    setSearchParams(next, { replace: true });
  }

  return {
    searchQuery: rawSearchQuery,
    category,
    sort,
    page,
    minPrice,
    maxPrice,
    free,
    minRating,
    products: data?.products.content ?? [],
    totalElements: data?.products.totalElements ?? 0,
    totalPages: data?.products.totalPages ?? 0,
    pageNumber: data?.products.pageNumber ?? 0,
    loading,
    error,
    refetch,
    setSearchQuery: (q: string) => updateParams({ q }),
    setCategory: (slug: string | undefined) => updateParams({ category: slug }),
    setSort: (sortBy: ProductSortBy | undefined) => updateParams({ sort: sortBy }),
    setFree: (isFree: boolean) => updateParams({ free: isFree ? 'true' : undefined }),
    setMinRating: (rating: number | undefined) => updateParams({ minRating: rating ? String(rating) : undefined }),
    setPage: (nextPage: number) => updateParams({ page: String(nextPage) }, false),
  };
}
