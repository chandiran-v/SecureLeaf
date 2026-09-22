import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MockedProvider } from '@apollo/client/testing';
import { describe, it, expect, vi } from 'vitest';
import ProductDetailPage from './ProductDetailPage';
import { PRODUCT_DETAIL } from '../../graphql/queries/marketplace.queries';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../lib/restClient', () => ({
  default: { get: vi.fn().mockReturnValue(new Promise(() => {})) }, // never resolves — keep PreviewPane in loading state
}));

const product = {
  __typename: 'Product',
  id: '1',
  title: 'Detail Book',
  description: 'A great read',
  pricePaise: 4999,
  status: 'LIVE',
  creator: { __typename: 'CreatorSummary', id: 'c1', displayName: 'Jane' },
  category: { __typename: 'Category', id: 'cat1', name: 'Fiction', slug: 'fiction' },
  tags: ['adventure'],
  thumbnailUrl: null,
  averageRating: 4.5,
  totalSales: 12,
  freePreviewPages: 3,
  pageCount: 10,
  createdAt: new Date().toISOString(),
};

function renderDetail(mocks: any[]) {
  render(
    <MemoryRouter initialEntries={['/product/1']}>
      <MockedProvider mocks={mocks}>
        <Routes>
          <Route path="/product/:id" element={<ProductDetailPage />} />
        </Routes>
      </MockedProvider>
    </MemoryRouter>
  );
}

describe('ProductDetailPage', () => {
  it('shows a disabled Buy button with a Phase 4 title once the product loads', async () => {
    const mocks = [
      {
        request: { query: PRODUCT_DETAIL, variables: { id: '1' } },
        result: { data: { product } },
      },
    ];
    renderDetail(mocks);

    await waitFor(() => expect(screen.getByText('Detail Book')).toBeInTheDocument());

    const buyButton = screen.getByRole('button', { name: /Buy/i });
    expect(buyButton).toBeDisabled();
    expect(buyButton).toHaveAttribute('title', 'Coming in Phase 4');
    expect(screen.getByText(/Jane/)).toBeInTheDocument();
  });

  it('shows a not-found state when the product does not exist', async () => {
    const mocks = [
      {
        request: { query: PRODUCT_DETAIL, variables: { id: '1' } },
        result: { data: { product: null } },
      },
    ];
    renderDetail(mocks);

    await waitFor(() => expect(screen.getByText(/Product not found/i)).toBeInTheDocument());
  });
});
