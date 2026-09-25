import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation } from '@apollo/client';
import { START_VIEWER_SESSION, VIEWER_HEARTBEAT } from '../../graphql/mutations/viewer.mutations';
import { useAuthStore } from '../../store/authStore';
import { getOrCreateDeviceFingerprint } from '../../lib/deviceFingerprint';
import type { ViewerHeartbeat, ViewerSession } from '../../types';

export type ViewerSessionUiStatus = 'starting' | 'active' | 'superseded' | 'expired' | 'error';

export interface UseViewerSessionResult {
  status: ViewerSessionUiStatus;
  session: ViewerSession | null;
  /** e.g. `NOT_ENTITLED` when startViewerSession itself was refused. Null for any other failure. */
  errorCode: string | null;
  /** Starts a brand-new session, taking over from whatever the "another device" was doing
   *  (D3's last-writer-wins). Used both by the takeover panel's button and, silently, once, when
   *  a lease simply lapses (D4). */
  restart: () => void;
  /** A tile fetch can discover SUPERSEDED (409) faster than the next heartbeat would — this lets
   *  useSecureTile push that fact straight into this hook's status. */
  notifySuperseded: () => void;
}

const GRAPHQL_URL = (import.meta.env.VITE_GRAPHQL_URL as string | undefined) ?? '/graphql';

const END_VIEWER_SESSION_QUERY =
  'mutation EndViewerSession($sessionToken: String!) { endViewerSession(sessionToken: $sessionToken) }';

/**
 * D3 — fires on `pagehide` (tab close/refresh/navigation away) as well as on unmount, since a
 * closed tab never runs React's cleanup. Deliberately a raw `fetch(..., { keepalive: true })`
 * rather than `navigator.sendBeacon`: sendBeacon cannot attach an `Authorization` header, and
 * every viewer endpoint requires one — there is no anonymous way to end a session.
 */
function endSessionBestEffort(sessionToken: string): void {
  const accessToken = useAuthStore.getState().accessToken;
  void fetch(GRAPHQL_URL, {
    method: 'POST',
    keepalive: true,
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: JSON.stringify({ query: END_VIEWER_SESSION_QUERY, variables: { sessionToken } }),
  }).catch(() => undefined);
}

function graphQLErrorCode(error: unknown): string | null {
  const withGraphQLErrors = error as { graphQLErrors?: { extensions?: { code?: string } }[] };
  return withGraphQLErrors?.graphQLErrors?.[0]?.extensions?.code ?? null;
}

/** D3 — owns one viewer session's whole lifecycle: start, heartbeat, takeover, and cleanup. */
export function useViewerSession(productId: string): UseViewerSessionResult {
  const [status, setStatus] = useState<ViewerSessionUiStatus>('starting');
  const [session, setSession] = useState<ViewerSession | null>(null);
  const [errorCode, setErrorCode] = useState<string | null>(null);

  const sessionRef = useRef<ViewerSession | null>(null);
  const hasAutoRestartedRef = useRef(false);

  const [startViewerSessionMutation] = useMutation<{ startViewerSession: ViewerSession }>(START_VIEWER_SESSION);
  const [heartbeatMutation] = useMutation<{ viewerHeartbeat: ViewerHeartbeat }>(VIEWER_HEARTBEAT);

  const start = useCallback(async () => {
    setStatus('starting');
    setErrorCode(null);
    try {
      const { data } = await startViewerSessionMutation({
        variables: { productId, deviceFingerprint: getOrCreateDeviceFingerprint() },
      });
      if (data?.startViewerSession) {
        sessionRef.current = data.startViewerSession;
        setSession(data.startViewerSession);
        setStatus('active');
      }
    } catch (err) {
      setErrorCode(graphQLErrorCode(err));
      setStatus('error');
    }
  }, [productId, startViewerSessionMutation]);

  const restart = useCallback(() => {
    hasAutoRestartedRef.current = false;
    void start();
  }, [start]);

  const notifySuperseded = useCallback(() => setStatus('superseded'), []);

  // Kick off (or restart, on productId change) a session.
  useEffect(() => {
    void start();
    // `start` is stable for a given productId; re-running it whenever its identity changes
    // (i.e. only when productId changes) is exactly the intent here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productId]);

  // D4 — heartbeat every heartbeatIntervalSeconds; cleared and rebuilt whenever the session
  // (token or interval) changes, e.g. after a restart.
  useEffect(() => {
    if (!session) return undefined;

    const tick = async () => {
      try {
        const { data } = await heartbeatMutation({ variables: { sessionToken: session.sessionToken } });
        const heartbeat = data?.viewerHeartbeat;
        if (!heartbeat) return;

        if (heartbeat.status === 'SUPERSEDED') {
          setStatus('superseded');
        } else if (heartbeat.status === 'EXPIRED') {
          // D4 — restart silently, once. If the brand-new session *also* lapses (e.g. the
          // buyer's laptop was asleep for minutes), stop retrying automatically and show the
          // panel instead of looping forever.
          if (!hasAutoRestartedRef.current) {
            hasAutoRestartedRef.current = true;
            void start();
          } else {
            setStatus('expired');
          }
        }
      } catch {
        // A transient network hiccup shouldn't kill an otherwise-healthy session; the next
        // scheduled heartbeat will simply try again.
      }
    };

    const intervalId = window.setInterval(() => void tick(), session.heartbeatIntervalSeconds * 1000);
    return () => window.clearInterval(intervalId);
  }, [session, heartbeatMutation, start]);

  // D3 — end the session however the reader leaves: an in-app navigation (unmount) or the tab
  // itself closing/refreshing (pagehide, which unmount alone would never see).
  useEffect(() => {
    const handlePageHide = () => {
      if (sessionRef.current) endSessionBestEffort(sessionRef.current.sessionToken);
    };
    window.addEventListener('pagehide', handlePageHide);
    return () => {
      window.removeEventListener('pagehide', handlePageHide);
      if (sessionRef.current) endSessionBestEffort(sessionRef.current.sessionToken);
    };
  }, []);

  return { status, session, errorCode, restart, notifySuperseded };
}
