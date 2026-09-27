import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { AxiosError } from 'axios';
import restClient from './restClient';
import * as session from './session';
import { useAuthStore } from '../store/authStore';

/** Fake transport: answers each request with the next status in the list, recording the token sent. */
function fakeAdapter(statuses: number[], seenTokens: (string | undefined)[]): AxiosAdapter {
  return async (config: InternalAxiosRequestConfig) => {
    seenTokens.push(config.headers?.Authorization as string | undefined);
    const status = statuses.shift() ?? 200;
    const response: AxiosResponse = {
      data: status === 401 ? { code: 'TOKEN_EXPIRED' } : { ok: true },
      status,
      statusText: String(status),
      headers: {},
      config,
    };
    if (status >= 400) throw new AxiosError('failed', String(status), config, null, response);
    return response;
  };
}

describe('restClient 401 handling', () => {
  const originalAdapter = restClient.defaults.adapter;

  beforeEach(() => {
    useAuthStore.getState().setAuth(
      { id: '1', email: 'u@x.com', displayName: 'U', roles: ['CREATOR'], createdAt: '' },
      'old-access',
      'old-refresh'
    );
  });

  afterEach(() => {
    restClient.defaults.adapter = originalAdapter;
    vi.restoreAllMocks();
  });

  it('refreshes the login and retries the request once with the new token', async () => {
    const seen: (string | undefined)[] = [];
    restClient.defaults.adapter = fakeAdapter([401, 200], seen);
    vi.spyOn(session, 'recoverFromUnauthorized').mockImplementation(async () => {
      useAuthStore.setState({ accessToken: 'new-access' });
      return true;
    });

    const response = await restClient.post('/products/1/document');

    expect(response.status).toBe(200);
    expect(seen).toEqual(['Bearer old-access', 'Bearer new-access']);
    expect(session.recoverFromUnauthorized).toHaveBeenCalledWith('TOKEN_EXPIRED');
  });

  it('gives up (no retry loop) when the session could not be recovered', async () => {
    const seen: (string | undefined)[] = [];
    restClient.defaults.adapter = fakeAdapter([401, 401, 401], seen);
    vi.spyOn(session, 'recoverFromUnauthorized').mockResolvedValue(false);

    await expect(restClient.post('/products/1/document')).rejects.toMatchObject({ response: { status: 401 } });
    expect(seen).toHaveLength(1);
  });

  it('leaves other errors alone', async () => {
    const seen: (string | undefined)[] = [];
    restClient.defaults.adapter = fakeAdapter([403], seen);
    const recover = vi.spyOn(session, 'recoverFromUnauthorized');

    await expect(restClient.post('/products/1/document')).rejects.toMatchObject({ response: { status: 403 } });
    expect(recover).not.toHaveBeenCalled();
  });
});
