import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { recoverFromUnauthorized, reasonFor } from './session';
import { useAuthStore } from '../store/authStore';
import { useSessionStore } from '../store/sessionStore';

const user = { id: '1', email: 'u@x.com', displayName: 'U', roles: ['BUYER' as const], createdAt: '' };

function signIn() {
  useAuthStore.getState().setAuth(user, 'old-access', 'old-refresh');
}

function refreshResponse(body: unknown) {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

describe('recoverFromUnauthorized', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    useAuthStore.getState().clearAuth();
    useSessionStore.setState({ endedReason: null });
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('refreshes once and stores the new tokens', async () => {
    signIn();
    fetchMock.mockResolvedValue(
      refreshResponse({ data: { refreshToken: { accessToken: 'new-access', refreshToken: 'new-refresh', user } } })
    );

    await expect(recoverFromUnauthorized('TOKEN_EXPIRED')).resolves.toBe(true);

    expect(useAuthStore.getState().accessToken).toBe('new-access');
    expect(useAuthStore.getState().refreshToken).toBe('new-refresh');
    expect(useSessionStore.getState().endedReason).toBeNull();
  });

  it('shares ONE refresh between concurrent 401s (no wasted rotations, no reliance on the reuse grace window)', async () => {
    signIn();
    let resolveFetch: (r: Response) => void = () => undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => (resolveFetch = resolve)));

    const results = Promise.all([
      recoverFromUnauthorized('TOKEN_EXPIRED'),
      recoverFromUnauthorized('TOKEN_EXPIRED'),
      recoverFromUnauthorized('TOKEN_EXPIRED'),
    ]);
    resolveFetch(refreshResponse({ data: { refreshToken: { accessToken: 'a', refreshToken: 'r', user } } }));

    await expect(results).resolves.toEqual([true, true, true]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('ends the session as "expired" when the refresh token has run out', async () => {
    signIn();
    fetchMock.mockResolvedValue(
      refreshResponse({ data: { refreshToken: null }, errors: [{ extensions: { code: 'TOKEN_EXPIRED' } }] })
    );

    await expect(recoverFromUnauthorized('TOKEN_EXPIRED')).resolves.toBe(false);

    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useSessionStore.getState().endedReason).toBe('expired');
  });

  it('ends the session as "invalid" when the refresh token was revoked or reused', async () => {
    signIn();
    fetchMock.mockResolvedValue(
      refreshResponse({ data: { refreshToken: null }, errors: [{ extensions: { code: 'TOKEN_REUSE' } }] })
    );

    await recoverFromUnauthorized('TOKEN_EXPIRED');

    expect(useSessionStore.getState().endedReason).toBe('invalid');
  });

  it('does nothing for a visitor who was never signed in', async () => {
    await expect(recoverFromUnauthorized('TOKEN_EXPIRED')).resolves.toBe(false);

    expect(fetchMock).not.toHaveBeenCalled();
    expect(useSessionStore.getState().endedReason).toBeNull();
  });

  it('maps server codes to the reason shown to the user', () => {
    expect(reasonFor('TOKEN_EXPIRED')).toBe('expired');
    expect(reasonFor(null)).toBe('expired');
    expect(reasonFor('INVALID_TOKEN')).toBe('invalid');
    expect(reasonFor('TOKEN_REUSE')).toBe('invalid');
    expect(reasonFor('ACCOUNT_SUSPENDED')).toBe('invalid');
  });
});
