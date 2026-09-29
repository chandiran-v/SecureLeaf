import { useQuery } from '@apollo/client';
import { PLATFORM_INFO } from '../graphql/queries/commerce.queries';
import type { PlatformInfo } from '../types';

/**
 * Public platform facts (Phase 09B D8): is this deployment moving real money, or is it a demo?
 * `info` is null until the query answers (and stays null if it fails — the banner then simply
 * doesn't show; that's the safe direction for a purely informational element).
 */
export function usePlatformInfo() {
  const { data } = useQuery<{ platformInfo: PlatformInfo }>(PLATFORM_INFO, { fetchPolicy: 'cache-first' });
  return { info: data?.platformInfo ?? null };
}
