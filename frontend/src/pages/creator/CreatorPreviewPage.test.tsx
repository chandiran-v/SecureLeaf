import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MockedProvider } from '@apollo/client/testing';
import { AxiosError, AxiosHeaders } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CreatorPreviewPage from './CreatorPreviewPage';
import { MY_PRODUCTS } from '../../graphql/queries/product.queries';
import restClient from '../../lib/restClient';

vi.mock('../../lib/restClient', () => ({ default: { get: vi.fn() } }));

const product = {
  __typename: 'Product',
  id: '7',
  title: 'My Guide',
  description: 'desc',
  pricePaise: 1000,
  status: 'LIVE',
  creator: { __typename: 'CreatorSummary', id: 'c1', displayName: 'Casey' },
  category: { __typename: 'Category', id: 'g', name: 'Guides', slug: 'guides' },
  tags: [],
  thumbnailUrl: null,
  averageRating: null,
  totalSales: 0,
  freePreviewPages: 2,
  pageCount: 3,
  createdAt: '2026-09-01T00:00:00Z',
  salesCount: 0,
  netEarningsPaise: 0,
  processingStage: null,
  failureReason: null,
  takedownReason: null,
};

function renderPage(path = '/creator/products/7/view') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <MockedProvider mocks={[{ request: { query: MY_PRODUCTS }, result: { data: { myProducts: [product] } } }]}>
        <Routes>
          <Route path="/creator/products/:id/view" element={<CreatorPreviewPage />} />
        </Routes>
      </MockedProvider>
    </MemoryRouter>
  );
}

describe('CreatorPreviewPage', () => {
  beforeEach(() => {
    vi.mocked(restClient.get).mockReset();
    URL.createObjectURL = vi.fn(() => 'blob:page');
    URL.revokeObjectURL = vi.fn();
  });

  it('fetches the clean page from the owner endpoint and shows it as an image', async () => {
    vi.mocked(restClient.get).mockResolvedValue({ data: new Blob(['png']) });
    renderPage();

    const img = await screen.findByRole('img', { name: /page 1/i });
    expect(img).toHaveAttribute('src', 'blob:page');
    expect(restClient.get).toHaveBeenCalledWith('/creator/products/7/pages/1', { responseType: 'blob' });
    expect(await screen.findByText('Page 1 / 3')).toBeInTheDocument();
    expect(screen.getByText(/no watermark/i)).toBeInTheDocument();
  });

  it('Next loads the following page', async () => {
    vi.mocked(restClient.get).mockResolvedValue({ data: new Blob(['png']) });
    renderPage();
    await screen.findByText('Page 1 / 3');

    fireEvent.click(screen.getByRole('button', { name: /next page/i }));

    await waitFor(() =>
      expect(restClient.get).toHaveBeenCalledWith('/creator/products/7/pages/2', { responseType: 'blob' })
    );
  });

  it('shows a slow-down message on 429 and retries on demand', async () => {
    const tooMany = new AxiosError('429', 'ERR', undefined, undefined, {
      status: 429, statusText: '', headers: {}, config: { headers: new AxiosHeaders() }, data: null,
    });
    vi.mocked(restClient.get).mockRejectedValueOnce(tooMany).mockResolvedValue({ data: new Blob(['png']) });
    renderPage();

    expect(await screen.findByText(/turning pages too fast/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /try again/i }));
    expect(await screen.findByRole('img', { name: /page 1/i })).toBeInTheDocument();
  });
});
