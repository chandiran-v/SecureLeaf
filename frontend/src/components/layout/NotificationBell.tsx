import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@apollo/client';
import { MY_NOTIFICATIONS } from '../../graphql/queries/commerce.queries';
import { MARK_ALL_NOTIFICATIONS_READ, MARK_NOTIFICATION_READ } from '../../graphql/mutations/commerce.mutations';
import { useNotificationStream } from '../../hooks/useNotificationStream';
import type { Notification } from '../../types';

const FALLBACK_POLL_INTERVAL_MS = 60_000;
const TOAST_DURATION_MS = 5_000;

/**
 * Header bell with an unread badge (PAY-08, NOTIF-04).
 *
 * Real-time via SSE (Phase 6, D7/D8): {@link useNotificationStream} opens an EventSource and
 * prepends every incoming notification straight into the `myNotifications` Apollo cache entry,
 * so this component doesn't poll at all while the stream is healthy. Polling is only the
 * fallback, after 3 failed reconnects — see the hook's javadoc-equivalent comment.
 */
export default function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [toast, setToast] = useState<Notification | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const toastTimerRef = useRef<number>();

  const { fallbackPolling } = useNotificationStream((notification) => {
    setToast(notification);
    window.clearTimeout(toastTimerRef.current);
    toastTimerRef.current = window.setTimeout(() => setToast(null), TOAST_DURATION_MS);
  });

  const { data } = useQuery<{ myNotifications: Notification[] }>(MY_NOTIFICATIONS, {
    pollInterval: fallbackPolling ? FALLBACK_POLL_INTERVAL_MS : 0,
    fetchPolicy: 'cache-and-network',
  });
  const [markRead] = useMutation(MARK_NOTIFICATION_READ);
  const [markAllRead] = useMutation(MARK_ALL_NOTIFICATIONS_READ);

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

  useEffect(() => () => window.clearTimeout(toastTimerRef.current), []);

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

  const handleMarkAllRead = () => {
    if (unread === 0) return;
    void markAllRead({
      optimisticResponse: { markAllNotificationsRead: unread },
      update: (cache) => {
        notifications.forEach((n) => {
          cache.modify({
            id: cache.identify({ __typename: 'Notification', id: n.id }),
            fields: { isRead: () => true },
          });
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
          <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Notifications</p>
            {unread > 0 && (
              <button
                type="button"
                onClick={handleMarkAllRead}
                className="text-xs font-medium text-emerald-600 hover:text-emerald-700"
              >
                Mark all read
              </button>
            )}
          </div>
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

      {toast && (
        <div
          role="status"
          className="fixed bottom-4 right-4 z-50 w-72 bg-white rounded-xl border border-gray-200 shadow-lg px-4 py-3"
        >
          <p className="text-sm font-medium text-gray-900">{toast.title}</p>
          {toast.body && <p className="text-xs text-gray-500 mt-0.5">{toast.body}</p>}
        </div>
      )}
    </div>
  );
}
