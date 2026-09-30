/**
 * Phase 11, D7 — client side of the server's rate limit.
 *
 * The backend answers a request over the limit with HTTP 429 + `Retry-After: <seconds>` (tile
 * endpoint) or a GraphQL error `RATE_LIMITED` with `extensions.retryAfterSeconds` (viewerPageUrl).
 * A real reader never sees either; the viewer handles the rare case by waiting exactly as long
 * as the server asked, retrying ONCE, and showing a subtle "Slow down…" hint meanwhile.
 */

/** Longest wait we honour: a bad header must never freeze the reader for minutes. */
const MAX_WAIT_SECONDS = 30;

export class RateLimitedError extends Error {
  constructor(public readonly retryAfterSeconds: number) {
    super(`Rate limited — retry after ${retryAfterSeconds}s`);
  }
}

/** `Retry-After` header (delta-seconds) -> whole seconds in [1, 30]; anything unparseable -> 1. */
export function parseRetryAfterSeconds(header: string | null | undefined): number {
  const seconds = Number(header);
  if (!Number.isFinite(seconds) || seconds < 1) return 1;
  return Math.min(Math.ceil(seconds), MAX_WAIT_SECONDS);
}

/** Pulls `RATE_LIMITED` out of an Apollo error (graphQLErrors[].extensions), if that is what it is. */
export function rateLimitedFromGraphQlError(error: unknown): RateLimitedError | null {
  const graphQLErrors = (error as { graphQLErrors?: ReadonlyArray<{ extensions?: Record<string, unknown> }> } | null)
    ?.graphQLErrors;
  const hit = graphQLErrors?.find((e) => e.extensions?.code === 'RATE_LIMITED');
  if (!hit) return null;
  return new RateLimitedError(parseRetryAfterSeconds(String(hit.extensions?.retryAfterSeconds ?? '')));
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Runs `operation`; if it throws {@link RateLimitedError}, raises the hint, waits `retryAfter`,
 * runs it once more and lowers the hint. A second RateLimitedError propagates (no retry loop —
 * hammering a limiter that just said no is exactly what it exists to stop).
 */
export async function retryOnceOnRateLimit<T>(
  operation: () => Promise<T>,
  onSlowDown: (active: boolean) => void,
  sleep: (ms: number) => Promise<void> = defaultSleep
): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    if (!(error instanceof RateLimitedError)) throw error;
    onSlowDown(true);
    try {
      await sleep(error.retryAfterSeconds * 1000);
      return await operation();
    } finally {
      onSlowDown(false);
    }
  }
}

/**
 * Phase 12, D4 — the server is overloaded (503 + `Retry-After`), which is not the same as the
 * client being over its limit (429): nothing here was misused, so the reader retries a couple of
 * times, but with a JITTERED, growing wait so a crowd of readers all shed at once doesn't come
 * back in lock-step and overload the render pool again.
 */
export class ServerBusyError extends Error {
  constructor(public readonly retryAfterSeconds: number) {
    super(`Server busy — retry after ${retryAfterSeconds}s`);
  }
}

/** Retries after the first attempt (spec: at most 2, then give up). */
export const MAX_BUSY_RETRIES = 2;

/** Wait before retry number `attempt` (0-based): Retry-After x 2^attempt, scaled by 0.5-1.5. */
export function busyBackoffMs(retryAfterSeconds: number, attempt: number, random: () => number = Math.random): number {
  return Math.round(retryAfterSeconds * 1000 * 2 ** attempt * (0.5 + random()));
}

/**
 * Runs `operation`; while it throws {@link ServerBusyError}, raises the "busy" hint, waits a
 * jittered backoff and tries again, at most {@link MAX_BUSY_RETRIES} times. The error from the
 * last attempt propagates so the caller can show its final message.
 */
export async function retryOnServerBusy<T>(
  operation: () => Promise<T>,
  onBusy: (active: boolean) => void,
  sleep: (ms: number) => Promise<void> = defaultSleep,
  random: () => number = Math.random
): Promise<T> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      const result = await operation();
      if (attempt > 0) onBusy(false);
      return result;
    } catch (error) {
      if (!(error instanceof ServerBusyError) || attempt >= MAX_BUSY_RETRIES) {
        onBusy(false);
        throw error;
      }
      onBusy(true);
      await sleep(busyBackoffMs(error.retryAfterSeconds, attempt, random));
    }
  }
}

/**
 * A rolling count of recent tile requests, so *optional* work (prefetching the next page) can
 * skip itself when the recent request rate is close to the server's budget (tile bucket: burst 5,
 * 2/s sustained). Prefetch is a nicety; spending the last tokens on it would make the page the
 * reader actually asked for wait.
 */
export class RequestBudget {
  private readonly stamps: number[] = [];

  constructor(
    private readonly windowMs = 1000,
    private readonly maxInWindow = 2
  ) {}

  record(now: number = Date.now()): void {
    this.stamps.push(now);
    this.prune(now);
  }

  /** True when one more request would still fit inside the sustained rate. */
  canSpend(now: number = Date.now()): boolean {
    this.prune(now);
    return this.stamps.length < this.maxInWindow;
  }

  private prune(now: number): void {
    while (this.stamps.length > 0 && now - this.stamps[0] >= this.windowMs) this.stamps.shift();
  }
}
