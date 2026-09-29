import * as Sentry from '@sentry/react';

/**
 * Phase 09D D8 — optional error monitoring. Does nothing unless VITE_SENTRY_DSN was set at build
 * time, so a build without it behaves exactly as before.
 */

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const JWT = /eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*/g;
const BEARER = /bearer\s+[A-Za-z0-9._~+/=-]+/gi;
const SECRET_PARAM = /([?&](?:sig|signature|token|ticket|access_token|refresh_token|password|key|secret)=)[^&\s"']*/gi;

/** Removes emails, tokens and signed-URL secrets from free text before it leaves the browser. */
export function scrubText(text: string): string {
  return text
    .replace(SECRET_PARAM, '$1[redacted]')
    .replace(JWT, '[jwt]')
    .replace(BEARER, 'Bearer [redacted]')
    .replace(EMAIL, '[email]');
}

/** Scrubs an event in place; exported for tests. */
export function scrubEvent<T extends Sentry.ErrorEvent>(event: T): T {
  delete event.user;
  if (event.message) {
    event.message = scrubText(event.message);
  }
  event.exception?.values?.forEach((ex) => {
    if (ex.value) {
      ex.value = scrubText(ex.value);
    }
  });
  if (event.request) {
    if (event.request.url) {
      event.request.url = scrubText(event.request.url);
    }
    delete event.request.query_string;
    delete event.request.cookies;
    delete event.request.headers;
    delete event.request.data;
  }
  event.breadcrumbs?.forEach((b) => {
    if (b.message) {
      b.message = scrubText(b.message);
    }
    delete b.data;
  });
  return event;
}

export function initMonitoring(): void {
  const dsn = import.meta.env.VITE_SENTRY_DSN as string | undefined;
  if (!dsn) {
    return;
  }
  Sentry.init({
    dsn,
    // PII collection is off by default in the SDK; beforeSend is the second layer.
    beforeSend: (event) => scrubEvent(event),
  });
}
