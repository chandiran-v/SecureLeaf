import { describe, it, expect, vi } from 'vitest';
import {
  RateLimitedError,
  ServerBusyError,
  busyBackoffMs,
  retryOnServerBusy,
  RequestBudget,
  parseRetryAfterSeconds,
  rateLimitedFromGraphQlError,
  retryOnceOnRateLimit,
} from './rateLimit';

describe('parseRetryAfterSeconds', () => {
  it('reads whole seconds, rounds up, clamps and defaults', () => {
    expect(parseRetryAfterSeconds('3')).toBe(3);
    expect(parseRetryAfterSeconds('1.2')).toBe(2);
    expect(parseRetryAfterSeconds('9999')).toBe(30);
    expect(parseRetryAfterSeconds(null)).toBe(1);
    expect(parseRetryAfterSeconds('soon')).toBe(1);
  });
});

describe('rateLimitedFromGraphQlError', () => {
  it('recognises RATE_LIMITED with retryAfterSeconds', () => {
    const err = { graphQLErrors: [{ extensions: { code: 'RATE_LIMITED', retryAfterSeconds: 2 } }] };
    expect(rateLimitedFromGraphQlError(err)?.retryAfterSeconds).toBe(2);
  });

  it('ignores other errors', () => {
    expect(rateLimitedFromGraphQlError({ graphQLErrors: [{ extensions: { code: 'NOT_ENTITLED' } }] })).toBeNull();
    expect(rateLimitedFromGraphQlError(new Error('boom'))).toBeNull();
  });
});

describe('retryOnceOnRateLimit', () => {
  it('waits Retry-After, shows the hint while waiting, then retries once', async () => {
    const op = vi.fn().mockRejectedValueOnce(new RateLimitedError(2)).mockResolvedValueOnce('ok');
    const hint = vi.fn();
    const sleep = vi.fn().mockResolvedValue(undefined);

    await expect(retryOnceOnRateLimit(op, hint, sleep)).resolves.toBe('ok');

    expect(sleep).toHaveBeenCalledWith(2000);
    expect(op).toHaveBeenCalledTimes(2);
    expect(hint.mock.calls).toEqual([[true], [false]]);
  });

  it('gives up after ONE retry', async () => {
    const op = vi.fn().mockRejectedValue(new RateLimitedError(1));
    const hint = vi.fn();

    await expect(retryOnceOnRateLimit(op, hint, async () => undefined)).rejects.toBeInstanceOf(RateLimitedError);

    expect(op).toHaveBeenCalledTimes(2);
    expect(hint).toHaveBeenLastCalledWith(false);
  });

  it('does not retry other errors', async () => {
    const op = vi.fn().mockRejectedValue(new Error('nope'));
    await expect(retryOnceOnRateLimit(op, vi.fn(), async () => undefined)).rejects.toThrow('nope');
    expect(op).toHaveBeenCalledTimes(1);
  });
});

describe('RequestBudget', () => {
  it('stops allowing optional work once the sustained rate is reached, and recovers', () => {
    const budget = new RequestBudget(1000, 2);
    expect(budget.canSpend(0)).toBe(true);
    budget.record(0);
    expect(budget.canSpend(100)).toBe(true);
    budget.record(100);
    expect(budget.canSpend(200)).toBe(false);
    expect(budget.canSpend(1001)).toBe(true); // the first stamp has left the window
  });
});

describe('retryOnServerBusy (Phase 12)', () => {
  it('backs off with jitter that grows per attempt', () => {
    expect(busyBackoffMs(1, 0, () => 0.5)).toBe(1000);
    expect(busyBackoffMs(1, 1, () => 0.5)).toBe(2000);
    expect(busyBackoffMs(1, 0, () => 0)).toBe(500);
    expect(busyBackoffMs(1, 0, () => 1)).toBe(1500);
  });

  it('retries a 503 and lowers the hint once it succeeds', async () => {
    const op = vi.fn().mockRejectedValueOnce(new ServerBusyError(1)).mockResolvedValueOnce('ok');
    const hint = vi.fn();
    const sleep = vi.fn().mockResolvedValue(undefined);

    await expect(retryOnServerBusy(op, hint, sleep, () => 0.5)).resolves.toBe('ok');

    expect(sleep).toHaveBeenCalledWith(1000);
    expect(hint.mock.calls).toEqual([[true], [false]]);
  });

  it('gives up after 2 retries (3 attempts) and rethrows', async () => {
    const op = vi.fn().mockRejectedValue(new ServerBusyError(1));
    const hint = vi.fn();
    const sleep = vi.fn().mockResolvedValue(undefined);

    await expect(retryOnServerBusy(op, hint, sleep, () => 0.5)).rejects.toBeInstanceOf(ServerBusyError);

    expect(op).toHaveBeenCalledTimes(3);
    expect(sleep.mock.calls).toEqual([[1000], [2000]]);
    expect(hint).toHaveBeenLastCalledWith(false);
  });

  it('does not retry other errors', async () => {
    const op = vi.fn().mockRejectedValue(new Error('nope'));
    await expect(retryOnServerBusy(op, vi.fn(), async () => undefined)).rejects.toThrow('nope');
    expect(op).toHaveBeenCalledTimes(1);
  });
});
