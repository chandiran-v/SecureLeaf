import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MockedProvider, type MockedResponse } from '@apollo/client/testing';
import { GraphQLError } from 'graphql';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ReaderPage from './ReaderPage';
import { START_VIEWER_SESSION, VIEWER_HEARTBEAT } from '../../graphql/mutations/viewer.mutations';
import { VIEWER_PAGE_URL } from '../../graphql/queries/viewer.queries';
import { useAuthStore } from '../../store/authStore';
import { useViewerStore } from '../../stores/viewerStore';

vi.mock('../../lib/deviceFingerprint', () => ({ getOrCreateDeviceFingerprint: () => 'device-1' }));

// Acceptance criteria references below are from docs/phases/phase-05b-secure-viewer-frontend.md.

function startSessionMock(overrides: {
  sessionToken?: string;
  pageCount?: number | null;
  heartbeatIntervalSeconds?: number;
} = {}): MockedResponse {
  return {
    request: { query: START_VIEWER_SESSION, variables: { productId: '1', deviceFingerprint: 'device-1' } },
    result: {
      data: {
        startViewerSession: {
          sessionId: '100',
          sessionToken: overrides.sessionToken ?? 'tok-1',
          productId: '1',
          pageCount: overrides.pageCount ?? 1,
          heartbeatIntervalSeconds: overrides.heartbeatIntervalSeconds ?? 15,
          expiresAt: new Date(Date.now() + 45_000).toISOString(),
        },
      },
    },
  };
}

function notEntitledMock(): MockedResponse {
  return {
    request: { query: START_VIEWER_SESSION, variables: { productId: '1', deviceFingerprint: 'device-1' } },
    result: {
      errors: [
        new GraphQLError('You do not have an active entitlement for this product.', {
          extensions: { code: 'NOT_ENTITLED' },
        }),
      ],
    },
  };
}

function pageUrlMock(sessionToken: string, pageNumber: number, url: string): MockedResponse {
  return {
    request: { query: VIEWER_PAGE_URL, variables: { sessionToken, pageNumber } },
    result: { data: { viewerPageUrl: { url, expiresAt: new Date(Date.now() + 30_000).toISOString() } } },
  };
}

/** Repeatable version — the same page can legitimately be fetched more than once (a page
 *  revisited after its prefetched bitmap was already drawn and closed). */
function pageUrlMockRepeatable(sessionToken: string, pageNumber: number): MockedResponse {
  return {
    request: { query: VIEWER_PAGE_URL, variables: { sessionToken, pageNumber } },
    maxUsageCount: Number.POSITIVE_INFINITY,
    result: () => ({
      data: {
        viewerPageUrl: {
          url: `/api/viewer/tiles/100/${pageNumber}?exp=1&sig=page-${pageNumber}`,
          expiresAt: new Date(Date.now() + 30_000).toISOString(),
        },
      },
    }),
  };
}

function heartbeatMock(sessionToken: string, status: 'ACTIVE' | 'SUPERSEDED' | 'EXPIRED'): MockedResponse {
  return {
    request: { query: VIEWER_HEARTBEAT, variables: { sessionToken } },
    maxUsageCount: Number.POSITIVE_INFINITY,
    result: () => ({ data: { viewerHeartbeat: { status, expiresAt: new Date().toISOString() } } }),
  };
}

function renderReader(mocks: MockedResponse[], initialEntry = '/read/1') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <MockedProvider mocks={mocks}>
        <Routes>
          <Route path="/read/:productId" element={<ReaderPage />} />
          <Route path="/product/:id" element={<div>Product page</div>} />
          <Route path="/library" element={<div>Library page</div>} />
        </Routes>
      </MockedProvider>
    </MemoryRouter>
  );
}

let fetchMock: ReturnType<typeof vi.fn>;
let drawImageSpy: ReturnType<typeof vi.fn>;

beforeEach(() => {
  useAuthStore.setState({
    isAuthenticated: true,
    accessToken: 't',
    refreshToken: 'r',
    user: { id: 'u1', email: 'u1@x.com', displayName: 'U1', roles: ['BUYER'], createdAt: '' },
  });
  useViewerStore.setState({ currentPage: 1, focusBlurred: false, devToolsBlurred: false });

  vi.stubGlobal(
    'createImageBitmap',
    vi.fn(async () => ({ width: 100, height: 140, close: vi.fn() }) as unknown as ImageBitmap)
  );

  drawImageSpy = vi.fn();
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    drawImage: drawImageSpy,
    clearRect: vi.fn(),
    setTransform: vi.fn(),
  } as unknown as CanvasRenderingContext2D);

  fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url.startsWith('/api/viewer/tiles/')) {
      return new Response(new Blob(['png-bytes'], { type: 'image/png' }), { status: 200 });
    }
    return new Response(JSON.stringify({ data: {} }), { status: 200 });
  });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ReaderPage', () => {
  // Criterion 1
  it('starts a session and draws page 1 through drawImage, with no <img> in the viewer DOM', async () => {
    const { container } = renderReader([
      startSessionMock({ pageCount: 1 }),
      pageUrlMock('tok-1', 1, '/api/viewer/tiles/100/1?exp=1&sig=abc'),
    ]);

    await waitFor(() => expect(drawImageSpy).toHaveBeenCalled());
    expect(container.querySelectorAll('img')).toHaveLength(0);
  });

  // Criterion 2
  it('prevents the context menu and marks the viewer non-selectable/non-draggable', async () => {
    const { container } = renderReader([
      startSessionMock({ pageCount: 1 }),
      pageUrlMock('tok-1', 1, '/api/viewer/tiles/100/1?exp=1&sig=abc'),
    ]);
    await waitFor(() => expect(drawImageSpy).toHaveBeenCalled());

    const root = container.firstElementChild as HTMLElement;
    expect(root.className).toContain('select-none');
    expect(fireEvent.contextMenu(root)).toBe(false); // false === preventDefault() was called

    const canvas = container.querySelector('canvas');
    expect(canvas).toHaveAttribute('draggable', 'false');
  });

  // Criterion 3
  it('blurs the page when the tab is hidden, and un-blurs it when visible again', async () => {
    renderReader([startSessionMock({ pageCount: 1 }), pageUrlMock('tok-1', 1, '/api/viewer/tiles/100/1?exp=1&sig=abc')]);
    await waitFor(() => expect(drawImageSpy).toHaveBeenCalled());

    act(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(await screen.findByText(/reading paused/i)).toBeInTheDocument();

    act(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await waitFor(() => expect(screen.queryByText(/reading paused/i)).not.toBeInTheDocument());
  });

  // Criterion 4
  it('shows the takeover panel on a SUPERSEDED heartbeat, and "Read here instead" starts a new session', async () => {
    renderReader([
      startSessionMock({ sessionToken: 'tok-1', pageCount: 1, heartbeatIntervalSeconds: 0.05 }),
      startSessionMock({ sessionToken: 'tok-2', pageCount: 1, heartbeatIntervalSeconds: 0.05 }),
      pageUrlMockRepeatable('tok-1', 1),
      pageUrlMockRepeatable('tok-2', 1),
      heartbeatMock('tok-1', 'SUPERSEDED'),
      heartbeatMock('tok-2', 'ACTIVE'),
    ]);

    expect(await screen.findByText(/opened on another device or tab/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /read here instead/i }));

    await waitFor(() => expect(screen.queryByText(/opened on another device or tab/i)).not.toBeInTheDocument());
    expect(await screen.findByText('Page 1 / 1')).toBeInTheDocument();
  });

  // Criterion 5 (409)
  it('shows the takeover panel when a tile fetch returns 409', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.startsWith('/api/viewer/tiles/')) {
        return new Response(JSON.stringify({ message: 'superseded', code: 'VIEWER_SESSION_SUPERSEDED' }), {
          status: 409,
        });
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 });
    });

    renderReader([startSessionMock({ pageCount: 1 }), pageUrlMockRepeatable('tok-1', 1)]);

    expect(await screen.findByText(/opened on another device or tab/i)).toBeInTheDocument();
  });

  // Criterion 5 (410)
  it('silently restarts once when a tile fetch returns 410', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('sig=first')) {
        return new Response(JSON.stringify({ message: 'expired', code: 'VIEWER_SESSION_EXPIRED' }), { status: 410 });
      }
      if (url.startsWith('/api/viewer/tiles/')) {
        return new Response(new Blob(['png-bytes'], { type: 'image/png' }), { status: 200 });
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 });
    });

    renderReader([
      startSessionMock({ sessionToken: 'tok-1', pageCount: 1 }),
      startSessionMock({ sessionToken: 'tok-2', pageCount: 1 }),
      pageUrlMock('tok-1', 1, '/api/viewer/tiles/100/1?exp=1&sig=first'),
      pageUrlMock('tok-2', 1, '/api/viewer/tiles/100/1?exp=1&sig=second'),
    ]);

    await waitFor(() => expect(drawImageSpy).toHaveBeenCalled());
    expect(screen.queryByText(/opened on another device or tab/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/reading session ended/i)).not.toBeInTheDocument();
  });

  // Criterion 6
  it('navigates with Prev/Next and arrow keys, clamped at both ends, updating the indicator', async () => {
    renderReader([
      startSessionMock({ pageCount: 3 }),
      pageUrlMockRepeatable('tok-1', 1),
      pageUrlMockRepeatable('tok-1', 2),
      pageUrlMockRepeatable('tok-1', 3),
    ]);

    expect(await screen.findByText('Page 1 / 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /previous page/i })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: /next page/i }));
    expect(await screen.findByText('Page 2 / 3')).toBeInTheDocument();

    fireEvent.keyDown(window, { key: 'ArrowRight' });
    expect(await screen.findByText('Page 3 / 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /next page/i })).toBeDisabled();

    // Already at the last page — further "next" input must not go out of range.
    fireEvent.keyDown(window, { key: 'ArrowRight' });
    expect(await screen.findByText('Page 3 / 3')).toBeInTheDocument();

    const drawCallsBeforeGoingBack = drawImageSpy.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: /previous page/i }));
    expect(await screen.findByText('Page 2 / 3')).toBeInTheDocument();
    // Page 2's prefetched bitmap was already drawn-and-closed on the way forward, so going back
    // re-fetches and redraws it rather than reusing a stale reference.
    await waitFor(() => expect(drawImageSpy.mock.calls.length).toBeGreaterThan(drawCallsBeforeGoingBack));

    fireEvent.keyDown(window, { key: 'ArrowLeft' });
    expect(await screen.findByText('Page 1 / 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /previous page/i })).toBeDisabled();
  });

  // Criterion 6 — ?page= on load
  it('resumes at the ?page= from the URL, clamped to the real page count', async () => {
    renderReader(
      [
        startSessionMock({ pageCount: 3 }),
        pageUrlMockRepeatable('tok-1', 2),
        pageUrlMockRepeatable('tok-1', 3), // prefetch of page 3 (page 2 < pageCount)
      ],
      '/read/1?page=2'
    );
    expect(await screen.findByText('Page 2 / 3')).toBeInTheDocument();
  });

  it('clamps an out-of-range ?page= down to the last real page', async () => {
    renderReader(
      [startSessionMock({ pageCount: 3 }), pageUrlMockRepeatable('tok-1', 3)],
      '/read/1?page=99'
    );
    expect(await screen.findByText('Page 3 / 3')).toBeInTheDocument();
  });

  // Criterion 7
  it('ends the session with a keepalive fetch and clears the heartbeat interval on unmount', async () => {
    const { unmount } = renderReader([
      startSessionMock({ pageCount: 1 }),
      pageUrlMock('tok-1', 1, '/api/viewer/tiles/100/1?exp=1&sig=abc'),
    ]);
    await waitFor(() => expect(drawImageSpy).toHaveBeenCalled());

    const clearIntervalSpy = vi.spyOn(window, 'clearInterval');
    unmount();

    expect(clearIntervalSpy).toHaveBeenCalled();
    await waitFor(() => {
      const endCall = fetchMock.mock.calls.find((call: unknown[]) => {
        const init = call[1] as RequestInit | undefined;
        return typeof init?.body === 'string' && init.body.includes('endViewerSession');
      });
      expect(endCall).toBeTruthy();
      expect(endCall?.[1]).toMatchObject({ keepalive: true });
    });
  });

  it('shows a "no access" panel when startViewerSession is refused', async () => {
    renderReader([notEntitledMock()]);
    expect(await screen.findByText(/don't have access to this/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /back to product page/i })).toHaveAttribute('href', '/product/1');
  });
});
