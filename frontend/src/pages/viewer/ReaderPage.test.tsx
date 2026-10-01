import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
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
          tileVariants: [
            { name: 'DESKTOP', widthPx: 1240 },
            { name: 'MOBILE', widthPx: 900 },
          ],
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
    request: { query: VIEWER_PAGE_URL, variables: { sessionToken, pageNumber, variant: 'DESKTOP' } },
    result: { data: { viewerPageUrl: { url, expiresAt: new Date(Date.now() + 30_000).toISOString(), links: [] } } },
  };
}

/** Repeatable version — the same page can legitimately be fetched more than once (a page
 *  revisited after its prefetched bitmap was already drawn and closed). */
function pageUrlMockRepeatable(sessionToken: string, pageNumber: number): MockedResponse {
  return {
    request: { query: VIEWER_PAGE_URL, variables: { sessionToken, pageNumber, variant: 'DESKTOP' } },
    maxUsageCount: Number.POSITIVE_INFINITY,
    result: () => ({
      data: {
        viewerPageUrl: {
          url: `/api/viewer/tiles/100/${pageNumber}?exp=1&sig=page-${pageNumber}`,
          expiresAt: new Date(Date.now() + 30_000).toISOString(),
          links: [],
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
  useViewerStore.setState({ currentPage: 1, zoom: 1, focusBlurred: false, devToolsBlurred: false });

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

  // Phase 11, D7 / criterion 7 — a 429 is retried once after Retry-After, with a "Slow down…" hint.
  it('waits Retry-After after a 429 tile response, shows the hint, then draws the page on the retry', async () => {
    let tileCalls = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.startsWith('/api/viewer/tiles/')) {
        tileCalls += 1;
        if (tileCalls === 1) {
          return new Response('{"code":"RATE_LIMITED"}', { status: 429, headers: { 'Retry-After': '1' } });
        }
        return new Response(new Blob(['png-bytes'], { type: 'image/png' }), { status: 200 });
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 });
    });

    renderReader([
      startSessionMock({ pageCount: 1 }),
      pageUrlMockRepeatable('tok-1', 1),
    ]);

    expect(await screen.findByText('Slow down…')).toBeInTheDocument();
    expect(drawImageSpy).not.toHaveBeenCalled();
    await waitFor(() => expect(drawImageSpy).toHaveBeenCalled(), { timeout: 4000 });
    expect(tileCalls).toBe(2);
    expect(screen.queryByText('Slow down…')).not.toBeInTheDocument();
  });

  // Phase 12 / criterion 5 — a 503 (render pool full) is retried with backoff and a "Busy" hint.
  it('retries a 503 tile response with backoff, shows the busy hint, then draws the page', async () => {
    let tileCalls = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.startsWith('/api/viewer/tiles/')) {
        tileCalls += 1;
        if (tileCalls === 1) {
          return new Response('{"code":"RENDER_UNAVAILABLE"}', { status: 503, headers: { 'Retry-After': '1' } });
        }
        return new Response(new Blob(['png-bytes'], { type: 'image/png' }), { status: 200 });
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 });
    });

    renderReader([startSessionMock({ pageCount: 1 }), pageUrlMockRepeatable('tok-1', 1)]);

    expect(await screen.findByText('Busy, retrying…')).toBeInTheDocument();
    await waitFor(() => expect(drawImageSpy).toHaveBeenCalled(), { timeout: 4000 });
    expect(tileCalls).toBe(2);
    expect(screen.queryByText('Busy, retrying…')).not.toBeInTheDocument();
  });

  // Phase 09C D5 — VIEW-07 regression: print is blocked ONLY while the reader is mounted.
  it('adds body.sl-reading while mounted (print blanks the page) and removes it on unmount', async () => {
    expect(document.body).not.toHaveClass('sl-reading');
    const { unmount } = renderReader([
      startSessionMock({ pageCount: 1 }),
      pageUrlMock('tok-1', 1, '/api/viewer/tiles/100/1?exp=1&sig=abc'),
    ]);
    expect(document.body).toHaveClass('sl-reading');
    await waitFor(() => expect(drawImageSpy).toHaveBeenCalled());
    unmount();
    expect(document.body).not.toHaveClass('sl-reading');
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

  // Zoom (added after 05B — see the "Zoom" addendum in docs/learning-notes/phase-05-secure-viewer.md)
  it('zooms with the toolbar buttons and keyboard, and "Fit" returns to 100%', async () => {
    renderReader([startSessionMock({ pageCount: 1 }), pageUrlMockRepeatable('tok-1', 1)]);
    expect(await screen.findByText('Page 1 / 1')).toBeInTheDocument();
    const level = screen.getByTestId('zoom-level');
    expect(level).toHaveTextContent('100%');
    expect(screen.getByRole('button', { name: /fit page/i })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: /zoom in/i }));
    expect(level).toHaveTextContent('125%');
    fireEvent.keyDown(window, { key: '+' });
    expect(level).toHaveTextContent('150%');
    fireEvent.keyDown(window, { key: '-' });
    fireEvent.click(screen.getByRole('button', { name: /zoom out/i }));
    fireEvent.click(screen.getByRole('button', { name: /zoom out/i }));
    expect(level).toHaveTextContent('75%');

    fireEvent.click(screen.getByRole('button', { name: /fit page/i }));
    expect(level).toHaveTextContent('100%');
    fireEvent.keyDown(window, { key: '=' });
    fireEvent.keyDown(window, { key: '0' });
    expect(level).toHaveTextContent('100%');
  });

  it('keeps Ctrl/Cmd +/- from zooming the whole browser tab while reading', async () => {
    renderReader([startSessionMock({ pageCount: 1 }), pageUrlMockRepeatable('tok-1', 1)]);
    expect(await screen.findByText('Page 1 / 1')).toBeInTheDocument();

    const notCancelled = fireEvent.keyDown(window, { key: '=', ctrlKey: true });

    expect(notCancelled).toBe(false);
    expect(screen.getByTestId('zoom-level')).toHaveTextContent('125%');
  });

  it('keeps the zoom level when turning pages', async () => {
    renderReader([
      startSessionMock({ pageCount: 2 }),
      pageUrlMockRepeatable('tok-1', 1),
      pageUrlMockRepeatable('tok-1', 2),
    ]);
    expect(await screen.findByText('Page 1 / 2')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /zoom in/i }));

    fireEvent.click(screen.getByRole('button', { name: /next page/i }));

    expect(await screen.findByText('Page 2 / 2')).toBeInTheDocument();
    expect(screen.getByTestId('zoom-level')).toHaveTextContent('125%');
  });

  // V8 — PDF links overlaid on the canvas
  it('overlays the page links: web links open in a new tab, page links jump, unsafe ones are dropped', async () => {
    const box = { left: 0.1, top: 0.1, width: 0.2, height: 0.05 };
    const linksMock: MockedResponse = {
      request: { query: VIEWER_PAGE_URL, variables: { sessionToken: 'tok-1', pageNumber: 1, variant: 'DESKTOP' } },
      result: {
        data: {
          viewerPageUrl: {
            url: '/api/viewer/tiles/100/1?exp=1&sig=page-1',
            expiresAt: new Date(Date.now() + 30_000).toISOString(),
            links: [
              { ...box, type: 'URL', url: 'https://example.com/ref', targetPage: null },
              { ...box, type: 'URL', url: 'javascript:alert(1)', targetPage: null },
              { ...box, type: 'PAGE', url: null, targetPage: 3 },
            ],
          },
        },
      },
    };
    renderReader([
      startSessionMock({ pageCount: 3 }),
      linksMock,
      pageUrlMockRepeatable('tok-1', 2),
      pageUrlMockRepeatable('tok-1', 3),
    ]);

    const webLink = await screen.findByRole('link', { name: /example\.com\/ref/ });
    expect(webLink).toHaveAttribute('href', 'https://example.com/ref');
    // Only links ON the page (the toolbar's "Exit" link doesn't count): the javascript: one never rendered.
    expect(within(screen.getByTestId('viewer-page')).getAllByRole('link')).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'Go to page 3' }));

    expect(await screen.findByText('Page 3 / 3')).toBeInTheDocument();
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
