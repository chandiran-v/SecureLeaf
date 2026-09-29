import { useQuery } from '@apollo/client';
import { CREATOR_STATEMENT } from '../graphql/queries/payout.queries';
import type { CreatorStatement } from '../types';

/** Phase 09C D4 — one month's statement ("YYYY-MM"). */
export function useCreatorStatement(month: string) {
  const { data, loading, error } = useQuery<{ creatorStatement: CreatorStatement }>(CREATOR_STATEMENT, {
    variables: { month },
    fetchPolicy: 'cache-and-network',
  });
  return { statement: data?.creatorStatement ?? null, loading, error };
}
