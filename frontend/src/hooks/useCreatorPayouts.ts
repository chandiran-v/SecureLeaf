import { useCallback } from 'react';
import { useMutation, useQuery } from '@apollo/client';
import { CREATOR_BALANCE, MY_PAYOUTS, MY_PAYOUT_DETAILS } from '../graphql/queries/payout.queries';
import { REQUEST_PAYOUT, UPDATE_PAYOUT_DETAILS } from '../graphql/mutations/payout.mutations';
import type { CreatorBalance, Payout, PayoutDetails } from '../types';

/**
 * Phase 09C D1/D2 — the creator's balance, payout history and payout details in one hook.
 * A payout request or a details edit refetches the balance and history, so the "available"
 * figure drops the moment a request is made (the server holds that money for the request).
 */
export function useCreatorPayouts() {
  const balanceQuery = useQuery<{ creatorBalance: CreatorBalance }>(CREATOR_BALANCE, { fetchPolicy: 'cache-and-network' });
  const payoutsQuery = useQuery<{ myPayouts: Payout[] }>(MY_PAYOUTS, { fetchPolicy: 'cache-and-network' });
  const detailsQuery = useQuery<{ myPayoutDetails: PayoutDetails }>(MY_PAYOUT_DETAILS, { fetchPolicy: 'cache-and-network' });

  const refetchQueries = [{ query: CREATOR_BALANCE }, { query: MY_PAYOUTS }];
  const [requestMutation, { loading: requesting }] = useMutation(REQUEST_PAYOUT, { refetchQueries });
  const [detailsMutation, { loading: savingDetails }] = useMutation(UPDATE_PAYOUT_DETAILS, {
    refetchQueries: [{ query: MY_PAYOUT_DETAILS }],
  });

  const requestPayout = useCallback(
    async (amountPaise: number) => {
      await requestMutation({ variables: { amountPaise } });
    },
    [requestMutation]
  );

  const savePayoutDetails = useCallback(
    async (details: PayoutDetails) => {
      await detailsMutation({ variables: { payoutUpi: details.payoutUpi ?? null, payoutEmail: details.payoutEmail ?? null } });
    },
    [detailsMutation]
  );

  return {
    balance: balanceQuery.data?.creatorBalance ?? null,
    balanceLoading: balanceQuery.loading,
    payouts: payoutsQuery.data?.myPayouts ?? [],
    details: detailsQuery.data?.myPayoutDetails ?? null,
    requestPayout,
    requesting,
    savePayoutDetails,
    savingDetails,
  };
}
