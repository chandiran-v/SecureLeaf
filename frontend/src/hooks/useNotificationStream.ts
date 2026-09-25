import { useEffect, useRef, useState } from 'react';
import { useApolloClient } from '@apollo/client';
import { MY_NOTIFICATIONS, NOTIFICATION_STREAM_TICKET } from '../graphql/queries/commerce.queries';
import type { Notification } from '../types';

const MAX_RECONNECT_ATTEMPTS = 3;
const RECONNECT_DELAY_MS = 1_000;

const STREAM_URL = (import.meta.env.VITE_NOTIFICATIONS_STREAM_URL as string | undefined)
  ?? '/api/notifications/stream';

/**
 * D7/D8 — subscribes to the SSE notification stream: fetch a fresh one-time ticket, open
 * {@code EventSource}, and prepend every incoming notification into the Apollo cache so
 * {@code myNotifications} (and anything reading it, like the bell) updates live.
 *
 * A fresh ticket is fetched for every (re)connect — a ticket is single-use and 30s-lived
 * (NotificationStreamTicketService), so reusing one across reconnects would just fail.
 *
 * After 3 failed reconnects in a row, gives up on SSE and reports `fallbackPolling: true` so
 * the caller can fall back to polling `myNotifications` instead (D8).
 */
export function useNotificationStream(onNotification?: (notification: Notification) => void) {
  const client = useApolloClient();
  const [fallbackPolling, setFallbackPolling] = useState(false);
  const attemptsRef = useRef(0);
  const onNotificationRef = useRef(onNotification);
  onNotificationRef.current = onNotification;

  useEffect(() => {
    if (fallbackPolling) return undefined;

    let cancelled = false;
    let source: EventSource | null = null;
    let retryTimer: number | undefined;

    const prepend = (notification: Notification) => {
      client.cache.updateQuery<{ myNotifications: Notification[] }>({ query: MY_NOTIFICATIONS }, (data) => {
        if (!data) return data;
        if (data.myNotifications.some((n) => n.id === notification.id)) return data;
        return { myNotifications: [notification, ...data.myNotifications] };
      });
      onNotificationRef.current?.(notification);
    };

    const scheduleReconnect = () => {
      if (cancelled) return;
      attemptsRef.current += 1;
      if (attemptsRef.current >= MAX_RECONNECT_ATTEMPTS) {
        setFallbackPolling(true);
        return;
      }
      retryTimer = window.setTimeout(() => void connect(), RECONNECT_DELAY_MS);
    };

    async function connect() {
      if (cancelled) return;
      let ticket: string | null | undefined;
      try {
        const { data } = await client.query<{ notificationStreamTicket: string }>({
          query: NOTIFICATION_STREAM_TICKET,
          fetchPolicy: 'network-only',
        });
        ticket = data?.notificationStreamTicket;
      } catch {
        scheduleReconnect();
        return;
      }
      if (!ticket || cancelled) return;

      source = new EventSource(`${STREAM_URL}?ticket=${encodeURIComponent(ticket)}`);

      source.addEventListener('notification', (event) => {
        attemptsRef.current = 0;
        try {
          prepend(JSON.parse((event as MessageEvent<string>).data) as Notification);
        } catch {
          // Malformed payload — ignore this one event, the connection itself is still healthy.
        }
      });

      source.onopen = () => {
        attemptsRef.current = 0;
      };

      source.onerror = () => {
        source?.close();
        scheduleReconnect();
      };
    }

    void connect();

    return () => {
      cancelled = true;
      window.clearTimeout(retryTimer);
      source?.close();
    };
  }, [client, fallbackPolling]);

  return { fallbackPolling };
}
