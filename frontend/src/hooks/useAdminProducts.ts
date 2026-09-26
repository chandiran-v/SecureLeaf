import { useMutation, useQuery } from '@apollo/client';
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ADMIN_PRODUCTS } from '../graphql/queries/admin.queries';
import { TAKE_DOWN_PRODUCT, RESTORE_PRODUCT } from '../graphql/mutations/admin.mutations';
import type { AdminProduct, AdminProductPage, ProductStatus } from '../types';

const PAGE_SIZE = 25;

/** D5 — same URL-driven-filter-state shape as useAdminUsers/useProductSearch. */
export function useAdminProducts() {
  const [searchParams, setSearchParams] = useSearchParams();

  const search = searchParams.get('search') ?? '';
  const status = (searchParams.get('status') as ProductStatus | null) ?? undefined;
  const page = Number(searchParams.get('page') ?? '0') || 0;

  const filter = useMemo(() => ({ search: search || undefined, status }), [search, status]);
  const variables = { filter, page, size: PAGE_SIZE };

  const { data, loading, error, refetch } = useQuery<{ adminProducts: AdminProductPage }>(ADMIN_PRODUCTS, {
    variables,
    fetchPolicy: 'cache-and-network',
  });

  const [takeDownProductMutation, { loading: takeDownLoading }] =
    useMutation<{ takeDownProduct: AdminProduct }>(TAKE_DOWN_PRODUCT, {
      refetchQueries: [{ query: ADMIN_PRODUCTS, variables }],
    });

  const [restoreProductMutation, { loading: restoreLoading }] =
    useMutation<{ restoreProduct: AdminProduct }>(RESTORE_PRODUCT, {
      refetchQueries: [{ query: ADMIN_PRODUCTS, variables }],
    });

  const takeDownProduct = useCallback(
    async (productId: string, reason: string) => {
      await takeDownProductMutation({ variables: { productId, reason } });
    },
    [takeDownProductMutation]
  );

  const restoreProduct = useCallback(
    async (productId: string) => {
      await restoreProductMutation({ variables: { productId } });
    },
    [restoreProductMutation]
  );

  function updateParams(patch: Record<string, string | undefined>, resetPage = true) {
    const next = new URLSearchParams(searchParams);
    Object.entries(patch).forEach(([key, value]) => {
      if (!value) next.delete(key);
      else next.set(key, value);
    });
    if (resetPage) next.delete('page');
    setSearchParams(next, { replace: true });
  }

  return {
    search,
    status,
    products: data?.adminProducts.content ?? [],
    totalElements: data?.adminProducts.totalElements ?? 0,
    totalPages: data?.adminProducts.totalPages ?? 0,
    pageNumber: data?.adminProducts.pageNumber ?? 0,
    loading,
    error,
    refetch,
    takeDownProduct,
    takeDownLoading,
    restoreProduct,
    restoreLoading,
    setSearch: (q: string) => updateParams({ search: q || undefined }),
    setStatus: (s: ProductStatus | undefined) => updateParams({ status: s }),
    setPage: (nextPage: number) => updateParams({ page: String(nextPage) }, false),
  };
}
