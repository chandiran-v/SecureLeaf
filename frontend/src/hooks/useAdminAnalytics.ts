import { useQuery } from '@apollo/client';
import { useState } from 'react';
import { PLATFORM_STATS } from '../graphql/queries/admin.queries';
import type { PlatformStats } from '../types';

/** D6 — the dashboard's window selector (7/30/90 days) just re-runs the same query with a
 *  different `days` argument; the server recomputes both all-time and windowed figures. */
export function useAdminAnalytics() {
  const [days, setDays] = useState(30);

  const { data, loading, error, refetch } = useQuery<{ platformStats: PlatformStats }>(PLATFORM_STATS, {
    variables: { days },
    fetchPolicy: 'cache-and-network',
  });

  return {
    stats: data?.platformStats,
    loading,
    error,
    days,
    setDays,
    refetch,
  };
}
