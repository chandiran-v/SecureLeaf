/**
 * Phase 9, D1 — one id per outgoing request (GraphQL or REST), sent as `X-Correlation-Id` and
 * echoed back by the backend on the same header. The backend puts it in every log line it writes
 * while handling the request (see CorrelationIdFilter); generating it here, client-side, means
 * the very first hop already carries it, instead of only starting once the request reaches the
 * server.
 */
export function newCorrelationId(): string {
  return crypto.randomUUID();
}

/** Appended to an error message shown to the user, so a support request can quote one id and
 *  have it be traceable through every log line the request touched, on either side of the wire. */
export function withReference(message: string, correlationId: string | undefined): string {
  return correlationId ? `${message} (Reference: ${correlationId})` : message;
}
