import { useCallback } from 'react';
import { useMutation, useQuery } from '@apollo/client';
import { useSearchParams } from 'react-router-dom';
import { ADMIN_PAYOUTS } from '../graphql/queries/payout.queries';
import { APPROVE_PAYOUT, MARK_PAYOUT_PAID, REJECT_PAYOUT } from '../graphql/mutations/payout.mutations';
import type { PayoutPage, PayoutStatus } from '../types';

const PAGE_SIZE = 25;

/** Phase 09C D3 — the admin payout queue, filtered by status (URL-driven like the other admin pages). */
export function useAdminPayouts() {
  const [searchParams, setSearchParams] = useSearchParams();
  const status = (searchParams.get('status') as PayoutStatus | null) ?? undefined;
  const page = Number(searchParams.get('page') ?? '0') || 0;
  const variables = { status, page, size: PAGE_SIZE };

  const { data, loading, error, refetch } = useQuery<{ adminPayouts: PayoutPage }>(ADMIN_PAYOUTS, {
    variables,
    fetchPolicy: 'cache-and-network',
  });

  const refetchQueries = [{ query: ADMIN_PAYOUTS, variables }];
  const [approveMutation, { loading: approving }] = useMutation(APPROVE_PAYOUT, { refetchQueries });
  const [paidMutation, { loading: markingPaid }] = useMutation(MARK_PAYOUT_PAID, { refetchQueries });
  const [rejectMutation, { loading: rejecting }] = useMutation(REJECT_PAYOUT, { refetchQueries });

  const approvePayout = useCallback(async (id: string) => { await approveMutation({ variables: { id } }); }, [approveMutation]);
  const markPayoutPaid = useCallback(
    async (id: string, reference: string) => { await paidMutation({ variables: { id, reference } }); },
    [paidMutation]
  );
  const rejectPayout = useCallback(
    async (id: string, reason: string) => { await rejectMutation({ variables: { id, reason } }); },
    [rejectMutation]
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
    status,
    payouts: data?.adminPayouts.content ?? [],
    totalPages: data?.adminPayouts.totalPages ?? 0,
    pageNumber: data?.adminPayouts.pageNumber ?? 0,
    loading,
    error,
    refetch,
    busy: approving || markingPaid || rejecting,
    approvePayout,
    markPayoutPaid,
    rejectPayout,
    setStatus: (s: PayoutStatus | undefined) => updateParams({ status: s }),
    setPage: (nextPage: number) => updateParams({ page: String(nextPage) }, false),
  };
}
