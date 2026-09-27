import { useQuery } from '@apollo/client';
import { useSearchParams } from 'react-router-dom';
import { ADMIN_ACTIONS } from '../graphql/queries/admin.queries';
import type { AdminActionPage } from '../types';

const PAGE_SIZE = 25;

/** D7 — paginated, newest-first read of the append-only admin_actions audit log. */
export function useAdminActions() {
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Number(searchParams.get('page') ?? '0') || 0;

  const { data, loading, error } = useQuery<{ adminActions: AdminActionPage }>(ADMIN_ACTIONS, {
    variables: { page, size: PAGE_SIZE },
    fetchPolicy: 'cache-and-network',
  });

  return {
    actions: data?.adminActions.content ?? [],
    totalElements: data?.adminActions.totalElements ?? 0,
    totalPages: data?.adminActions.totalPages ?? 0,
    pageNumber: data?.adminActions.pageNumber ?? 0,
    loading,
    error,
    setPage: (nextPage: number) => {
      const next = new URLSearchParams(searchParams);
      next.set('page', String(nextPage));
      setSearchParams(next, { replace: true });
    },
  };
}
