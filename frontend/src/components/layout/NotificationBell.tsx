import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@apollo/client';
import { MY_NOTIFICATIONS } from '../../graphql/queries/commerce.queries';
import { MARK_NOTIFICATION_READ } from '../../graphql/mutations/commerce.mutations';
import type { Notification } from '../../types';

const POLL_INTERVAL_MS = 30_000;

/**
 * Header bell with an unread badge (PAY-08).
 *
 * Polling, for now: the backend already publishes every notification to Redis Pub/Sub, but
 * nothing bridges Redis to the browser yet — Phase 6 adds a Server-Sent-Events stream and
 * this component swaps pollInterval for a subscription. Polling every 30s is the honest,
 * simple baseline: at worst a notification shows up half a minute late.
 */
export default function NotificationBell() {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const { data } = useQuery<{ myNotifications: Notification[] }>(MY_NOTIFICATIONS, {
    pollInterval: POLL_INTERVAL_MS,
    fetchPolicy: 'cache-and-network',
  });
  const [markRead] = useMutation(MARK_NOTIFICATION_READ);

  const notifications = data?.myNotifications ?? [];
  const unread = notifications.filter((n) => !n.isRead).length;

  // Close the dropdown on an outside click.
  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);

  const handleRead = (n: Notification) => {
    if (n.isRead) return;
    void markRead({
      variables: { id: n.id },
      // Flip the badge instantly instead of waiting for the next poll.
      optimisticResponse: { markNotificationRead: true },
      update: (cache) => {
        cache.modify({
          id: cache.identify({ __typename: 'Notification', id: n.id }),
          fields: { isRead: () => true },
        });
      },
    });
  };

  return (
    <div className="relative" ref={containerRef}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={unread > 0 ? `Notifications (${unread} unread)` : 'Notifications'}
        className="relative p-2 rounded-lg text-gray-500 hover:bg-gray-100 hover:text-gray-900 transition-colors"
      >
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6 6 0 10-12 0v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
        </svg>
        {unread > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-rose-500 text-white text-[10px] font-bold flex items-center justify-center">
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 mt-2 w-80 bg-white/95 backdrop-blur rounded-xl border border-gray-200 shadow-xl overflow-hidden z-50">
          <p className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide border-b border-gray-100">
            Notifications
          </p>
          {notifications.length === 0 ? (
            <p className="px-4 py-8 text-sm text-gray-400 text-center">Nothing yet.</p>
          ) : (
            <ul className="max-h-96 overflow-y-auto divide-y divide-gray-100">
              {notifications.map((n) => (
                <li key={n.id}>
                  <button
                    type="button"
                    onClick={() => handleRead(n)}
                    className={`w-full text-left px-4 py-3 hover:bg-gray-50 transition-colors ${n.isRead ? '' : 'bg-emerald-50/50'}`}
                  >
                    <div className="flex items-start gap-2">
                      {!n.isRead && <span className="mt-1.5 w-2 h-2 rounded-full bg-emerald-500 flex-shrink-0" />}
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-gray-900">{n.title}</p>
                        {n.body && <p className="text-xs text-gray-500 mt-0.5">{n.body}</p>}
                        <p className="text-[11px] text-gray-400 mt-1">
                          {new Date(n.createdAt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })}
                        </p>
                      </div>
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
