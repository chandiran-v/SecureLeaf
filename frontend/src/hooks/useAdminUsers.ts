import { useMutation, useQuery } from '@apollo/client';
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ADMIN_USERS } from '../graphql/queries/admin.queries';
import { SUSPEND_USER, REACTIVATE_USER } from '../graphql/mutations/admin.mutations';
import type { AccountStatus, AdminUser, AdminUserPage, UserRole } from '../types';

const PAGE_SIZE = 25;

/**
 * D3/D4 — same URL-driven-filter-state shape as useProductSearch: the querystring is the single
 * source of truth for search/role/status/page, so the admin users screen is shareable and
 * back-button-safe.
 */
export function useAdminUsers() {
  const [searchParams, setSearchParams] = useSearchParams();

  const search = searchParams.get('search') ?? '';
  const role = (searchParams.get('role') as UserRole | null) ?? undefined;
  const status = (searchParams.get('status') as AccountStatus | null) ?? undefined;
  const page = Number(searchParams.get('page') ?? '0') || 0;

  const filter = useMemo(
    () => ({ search: search || undefined, role, status }),
    [search, role, status]
  );

  const variables = { filter, page, size: PAGE_SIZE };

  const { data, loading, error, refetch } = useQuery<{ adminUsers: AdminUserPage }>(ADMIN_USERS, {
    variables,
    fetchPolicy: 'cache-and-network',
  });

  const [suspendUserMutation, { loading: suspendLoading }] =
    useMutation<{ suspendUser: AdminUser }>(SUSPEND_USER, {
      refetchQueries: [{ query: ADMIN_USERS, variables }],
    });

  const [reactivateUserMutation, { loading: reactivateLoading }] =
    useMutation<{ reactivateUser: AdminUser }>(REACTIVATE_USER, {
      refetchQueries: [{ query: ADMIN_USERS, variables }],
    });

  const suspendUser = useCallback(
    async (userId: string, reason: string) => {
      await suspendUserMutation({ variables: { userId, reason } });
    },
    [suspendUserMutation]
  );

  const reactivateUser = useCallback(
    async (userId: string) => {
      await reactivateUserMutation({ variables: { userId } });
    },
    [reactivateUserMutation]
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
    role,
    status,
    users: data?.adminUsers.content ?? [],
    totalElements: data?.adminUsers.totalElements ?? 0,
    totalPages: data?.adminUsers.totalPages ?? 0,
    pageNumber: data?.adminUsers.pageNumber ?? 0,
    loading,
    error,
    refetch,
    suspendUser,
    suspendLoading,
    reactivateUser,
    reactivateLoading,
    setSearch: (q: string) => updateParams({ search: q || undefined }),
    setRole: (r: UserRole | undefined) => updateParams({ role: r }),
    setStatus: (s: AccountStatus | undefined) => updateParams({ status: s }),
    setPage: (nextPage: number) => updateParams({ page: String(nextPage) }, false),
  };
}
