/**
 * One place that decides what to do when the server says "unauthorized" (HTTP 401):
 * refresh the access token and let the caller retry, or end the session and tell the user.
 * Apollo (apolloClient.ts), the REST client (restClient.ts) and the reader's tile fetch all use it.
 */
import { useAuthStore } from '../store/authStore';
import { useSessionStore, type SessionEndReason } from '../store/sessionStore';
import type { User } from '../types';

const GRAPHQL_URL = import.meta.env.VITE_GRAPHQL_URL ?? '/graphql';

const REFRESH_MUTATION = `mutation RefreshToken($token: String!) {
  refreshToken(token: $token) {
    accessToken
    refreshToken
    user { id email displayName roles createdAt }
  }
}`;

type RefreshResult = { ok: true } | { ok: false; code: string | null };

let inFlight: Promise<RefreshResult> | null = null;

/**
 * Exchanges the refresh token for a new access token.
 *
 * SINGLE-FLIGHT: concurrent callers share one request. The backend rotates refresh tokens and
 * treats a second use of an old one as theft (TOKEN_REUSE: every session revoked). It forgives
 * reuse within a 30-second grace window (Phase 1), but a page that fires three queries with an
 * expired token shouldn't lean on that: it would send three refreshes, burn three rotations,
 * and a slow network could push a retry past the window. One refresh, shared, avoids all of it.
 */
export function refreshSession(): Promise<RefreshResult> {
  if (!inFlight) {
    inFlight = doRefresh().finally(() => {
      inFlight = null;
    });
  }
  return inFlight;
}

async function doRefresh(): Promise<RefreshResult> {
  const refreshToken = useAuthStore.getState().refreshToken;
  if (!refreshToken) return { ok: false, code: null };
  try {
    const response = await fetch(GRAPHQL_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: REFRESH_MUTATION, variables: { token: refreshToken } }),
    });
    const result = (await response.json()) as {
      data?: { refreshToken?: { accessToken: string; refreshToken: string; user: User } | null };
      errors?: { extensions?: { code?: string } }[];
    };
    const payload = result.data?.refreshToken;
    if (!payload) return { ok: false, code: result.errors?.[0]?.extensions?.code ?? null };
    useAuthStore.getState().setAuth(payload.user, payload.accessToken, payload.refreshToken);
    return { ok: true };
  } catch {
    // Network failure: we don't know that the session is dead, so report it as "not refreshed"
    // without a code; the caller treats it like a timeout.
    return { ok: false, code: null };
  }
}

/** Server error codes → what we tell the user. Anything unrecognised reads as a plain timeout. */
export function reasonFor(code: string | null | undefined): SessionEndReason {
  switch (code) {
    case 'INVALID_TOKEN':
    case 'TOKEN_REUSE':
    case 'ACCOUNT_SUSPENDED':
    case 'ACCOUNT_DEACTIVATED':
      return 'invalid';
    default:
      return 'expired';
  }
}

/**
 * Call on any 401. Returns `true` when a fresh access token is in place and the caller should
 * retry its request once. Returns `false` when the session is over; in that case the user has
 * been signed out and SessionExpiredModal is showing.
 *
 * @param code the `code` from the 401 body (TOKEN_EXPIRED / INVALID_TOKEN), if any
 */
export async function recoverFromUnauthorized(code?: string | null): Promise<boolean> {
  if (!useAuthStore.getState().isAuthenticated) return false; // nothing to recover
  const refreshed = await refreshSession();
  if (refreshed.ok) return true;
  // Prefer the refresh failure's own reason (e.g. TOKEN_REUSE = revoked elsewhere); fall back
  // to what the original 401 said.
  useSessionStore.getState().endSession(reasonFor(refreshed.code ?? code));
  return false;
}
