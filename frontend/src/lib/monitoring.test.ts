import { describe, expect, it } from 'vitest';
import type { ErrorEvent } from '@sentry/react';
import { scrubEvent, scrubText } from './monitoring';

describe('monitoring scrubbing', () => {
  it('removes emails, tokens and signed URL secrets', () => {
    const clean = scrubText(
      'alice@example.com Bearer abc.def eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln /tiles/1?sig=SECRET&exp=9',
    );
    expect(clean).not.toMatch(/alice@example\.com|abc\.def|eyJhbGci|SECRET/);
    expect(clean).toContain('sig=[redacted]');
    expect(clean).toContain('exp=9');
  });

  it('cleans the event and drops user, cookies and headers', () => {
    const event = {
      type: undefined,
      user: { email: 'bob@example.com' },
      message: 'failed for bob@example.com',
      exception: { values: [{ value: 'password=x?token=abc' }] },
      request: { url: 'https://x.test/a?token=zzz', cookies: { a: 'b' }, headers: { h: 'v' } },
    } as ErrorEvent;
    const out = scrubEvent(event);
    expect(out.user).toBeUndefined();
    expect(out.message).not.toContain('bob@example.com');
    expect(out.exception?.values?.[0].value).not.toContain('abc');
    expect(out.request?.url).not.toContain('zzz');
    expect(out.request?.cookies).toBeUndefined();
    expect(out.request?.headers).toBeUndefined();
  });
});
