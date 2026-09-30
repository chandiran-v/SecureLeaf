import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MockedProvider } from '@apollo/client/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ProductVersionsPage from './ProductVersionsPage';
import { PRODUCT_VERSIONS } from '../../graphql/queries/product.queries';
import restClient from '../../lib/restClient';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));
vi.mock('../../lib/restClient', () => ({ default: { post: vi.fn() } }));

function version(overrides: Record<string, unknown> = {}) {
  return {
    __typename: 'DocumentVersion',
    id: '1',
    versionNumber: 1,
    createdAt: '2026-09-01T10:00:00Z',
    pageCount: 12,
    status: 'READY',
    updatePolicy: 'NEW_BUYERS_ONLY',
    buyerCount: 3,
    current: true,
    failureReason: null,
    migrationPending: false,
    ...overrides,
  };
}

function renderPage(responses: unknown[][]) {
  const mocks = responses.map((productVersions) => ({
    request: { query: PRODUCT_VERSIONS, variables: { productId: '5' } },
    result: { data: { productVersions } },
  }));
  render(
    <MemoryRouter initialEntries={['/creator/products/5/versions']}>
      <MockedProvider mocks={mocks}>
        <Routes>
          <Route path="/creator/products/:id/versions" element={<ProductVersionsPage />} />
        </Routes>
      </MockedProvider>
    </MemoryRouter>
  );
}

describe('ProductVersionsPage', () => {
  beforeEach(() => {
    vi.mocked(restClient.post).mockReset();
  });

  it('renders the version history with number, pages, status, policy and buyer count', async () => {
    renderPage([[
      version({ id: '2', versionNumber: 2, current: true, buyerCount: 1, updatePolicy: 'FREE_UPDATE_FOR_EXISTING' }),
      version({ id: '1', versionNumber: 1, current: false, buyerCount: 0 }),
    ]]);

    const v2 = await screen.findByTestId('version-row-2');
    expect(v2).toHaveTextContent('v2');
    expect(v2).toHaveTextContent('Current');
    expect(v2).toHaveTextContent('12');
    expect(v2).toHaveTextContent('Ready');
    expect(v2).toHaveTextContent('Free update for existing buyers');

    const v1 = screen.getByTestId('version-row-1');
    expect(v1).not.toHaveTextContent('Current');
    // Only a non-current version with no buyers can be retired.
    expect(v1).toHaveTextContent('Retire');
    expect(v2).not.toHaveTextContent('Retire');
  });

  it('does not offer to retire a version that still has buyers, and offers retry on a failed one', async () => {
    renderPage([[
      version({ id: '3', versionNumber: 3, current: false, status: 'FAILED', failureReason: 'PDF is encrypted.', buyerCount: 0 }),
      version({ id: '2', versionNumber: 2, current: true, buyerCount: 4 }),
      version({ id: '1', versionNumber: 1, current: false, buyerCount: 2 }),
    ]]);

    const v3 = await screen.findByTestId('version-row-3');
    expect(v3).toHaveTextContent('Failed');
    expect(v3).toHaveTextContent('PDF is encrypted.');
    expect(v3).toHaveTextContent('Retry');
    expect(screen.getByTestId('version-row-1')).not.toHaveTextContent('Retire');
  });

  it('walks the upload flow: uploading, then processing while the product stays live', async () => {
    let resolveUpload: () => void = () => {};
    vi.mocked(restClient.post).mockImplementation(() => new Promise((resolve) => {
      resolveUpload = () => resolve({ status: 202 });
    }));
    renderPage([
      [version()],
      [version({ id: '2', versionNumber: 2, current: false, status: 'PROCESSING', pageCount: null, buyerCount: 0 }), version({ current: true })],
    ]);
    await screen.findByTestId('version-row-1');

    const input = screen.getByLabelText('PDF file') as HTMLInputElement;
    const pdf = new File(['%PDF-1.4'], 'second-edition.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByLabelText('Existing buyers'), { target: { value: 'FREE_UPDATE_FOR_EXISTING' } });
    fireEvent.change(input, { target: { files: [pdf] } });
    fireEvent.click(screen.getByRole('button', { name: 'Upload version' }));

    await waitFor(() => expect(screen.getByTestId('upload-status')).toHaveTextContent(/uploading/i));
    const [url, body] = vi.mocked(restClient.post).mock.calls[0];
    expect(url).toBe('/products/5/versions');
    expect((body as FormData).get('updatePolicy')).toBe('FREE_UPDATE_FOR_EXISTING');

    resolveUpload();
    await waitFor(() => expect(screen.getByTestId('upload-status')).toHaveTextContent(/stays live on its current version/i));
    expect(await screen.findByTestId('version-row-2')).toHaveTextContent('Processing');
  });

  it('rejects a non-PDF client-side without calling the server', async () => {
    renderPage([[version()]]);
    await screen.findByTestId('version-row-1');

    fireEvent.change(screen.getByLabelText('PDF file'), {
      target: { files: [new File(['x'], 'notes.txt', { type: 'text/plain' })] },
    });

    expect(await screen.findByRole('alert')).toHaveTextContent(/only pdf/i);
    expect(screen.getByRole('button', { name: 'Upload version' })).toBeDisabled();
    expect(restClient.post).not.toHaveBeenCalled();
  });
});
