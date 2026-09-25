import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MockedProvider } from '@apollo/client/testing';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import NotificationBell from './NotificationBell';
import { MY_NOTIFICATIONS, NOTIFICATION_STREAM_TICKET } from '../../graphql/queries/commerce.queries';
import { MARK_ALL_NOTIFICATIONS_READ, MARK_NOTIFICATION_READ } from '../../graphql/mutations/commerce.mutations';

/** A minimal EventSource stand-in — enough for useNotificationStream's addEventListener/onerror/close usage. */
class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners: Record<string, ((event: MessageEvent<string>) => void)[]> = {};

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, callback: (event: MessageEvent<string>) => void) {
    (this.listeners[type] ??= []).push(callback);
  }

  close() {
    // no-op
  }

  emit(type: string, data: string) {
    this.listeners[type]?.forEach((cb) => cb({ data } as MessageEvent<string>));
  }
}

beforeEach(() => {
  MockEventSource.instances = [];
  vi.stubGlobal('EventSource', MockEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const ticketMock = {
  request: { query: NOTIFICATION_STREAM_TICKET },
  result: { data: { notificationStreamTicket: 'test-ticket' } },
};

function notification(overrides: Record<string, unknown> = {}) {
  return {
    __typename: 'Notification',
    id: '1',
    type: 'SALE_RECEIVED',
    title: 'New sale',
    body: 'Someone bought your product',
    isRead: false,
    createdAt: '2026-09-20T10:00:00Z',
    ...overrides,
  };
}

describe('NotificationBell', () => {
  it('prepends an incoming SSE event into the notification list', async () => {
    const mocks = [
      { request: { query: MY_NOTIFICATIONS }, result: { data: { myNotifications: [] } } },
      ticketMock,
    ];

    render(
      <MockedProvider mocks={mocks}>
        <NotificationBell />
      </MockedProvider>
    );

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));
    expect(MockEventSource.instances[0].url).toContain('ticket=test-ticket');

    act(() => {
      MockEventSource.instances[0].emit('notification', JSON.stringify(notification()));
    });

    // Badge shows 1 unread.
    expect(await screen.findByLabelText(/notifications \(1 unread\)/i)).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText(/notifications \(1 unread\)/i));
    // Appears twice: once in the dropdown list, once in the toast — both are expected here.
    await waitFor(() => expect(screen.getAllByText('New sale')).toHaveLength(2));
  });

  it('shows a toast for an incoming SSE event', async () => {
    const mocks = [
      { request: { query: MY_NOTIFICATIONS }, result: { data: { myNotifications: [] } } },
      ticketMock,
    ];

    render(
      <MockedProvider mocks={mocks}>
        <NotificationBell />
      </MockedProvider>
    );

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));
    act(() => {
      MockEventSource.instances[0].emit('notification', JSON.stringify(notification({ title: 'Toast title' })));
    });

    expect(await screen.findByRole('status')).toHaveTextContent('Toast title');
  });

  it('"Mark all read" clears the unread badge', async () => {
    const mocks = [
      {
        request: { query: MY_NOTIFICATIONS },
        result: { data: { myNotifications: [notification({ id: '1' }), notification({ id: '2' })] } },
      },
      ticketMock,
      {
        request: { query: MARK_ALL_NOTIFICATIONS_READ },
        result: { data: { markAllNotificationsRead: 2 } },
      },
    ];

    render(
      <MockedProvider mocks={mocks}>
        <NotificationBell />
      </MockedProvider>
    );

    expect(await screen.findByLabelText(/notifications \(2 unread\)/i)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText(/notifications \(2 unread\)/i));

    fireEvent.click(await screen.findByRole('button', { name: /mark all read/i }));

    await waitFor(() => expect(screen.getByLabelText('Notifications')).toBeInTheDocument());
  });

  it('marking a single notification read updates it optimistically', async () => {
    const mocks = [
      { request: { query: MY_NOTIFICATIONS }, result: { data: { myNotifications: [notification({ id: '1' })] } } },
      ticketMock,
      { request: { query: MARK_NOTIFICATION_READ, variables: { id: '1' } }, result: { data: { markNotificationRead: true } } },
    ];

    render(
      <MockedProvider mocks={mocks}>
        <NotificationBell />
      </MockedProvider>
    );

    fireEvent.click(await screen.findByLabelText(/notifications \(1 unread\)/i));
    fireEvent.click(await screen.findByText('New sale'));

    await waitFor(() => expect(screen.getByLabelText('Notifications')).toBeInTheDocument());
  });
});
