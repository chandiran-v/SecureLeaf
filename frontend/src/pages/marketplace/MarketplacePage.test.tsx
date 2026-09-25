import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MockedProvider } from '@apollo/client/testing';
import type { ApolloError } from '@apollo/client';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import MarketplacePage from './MarketplacePage';
import { useProductSearch } from '../../hooks/useProductSearch';
import { CATEGORIES } from '../../graphql/queries/marketplace.queries';
import type { Product } from '../../types';

vi.mock('../../hooks/useProductSearch');
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));

const categoriesMock = {
  request: { query: CATEGORIES },
  result: { data: { categories: [{ __typename: 'Category', id: '1', name: 'Fiction', slug: 'fiction' }] } },
};

function makeProduct(overrides: Partial<Product> = {}): Product {
  return {
    id: '1',
    title: 'Sample Book',
    description: 'desc',
    pricePaise: 1999,
    status: 'LIVE',
    creator: { id: 'c1', displayName: 'Jane' },
    category: { id: 'cat1', name: 'Fiction', slug: 'fiction' },
    tags: [],
    totalSales: 3,
    freePreviewPages: 2,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

function baseHookReturn(overrides: Partial<ReturnType<typeof useProductSearch>> = {}) {
  return {
    searchQuery: '',
    category: undefined,
    sort: undefined,
    page: 0,
    minPrice: null,
    maxPrice: null,
    free: false,
    products: [],
    totalElements: 0,
    totalPages: 0,
    pageNumber: 0,
    loading: false,
    error: undefined,
    refetch: vi.fn(),
    setSearchQuery: vi.fn(),
    setCategory: vi.fn(),
    setSort: vi.fn(),
    setFree: vi.fn(),
    setPage: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof useProductSearch>;
}

function renderPage() {
  render(
    <MemoryRouter>
      <MockedProvider mocks={[categoriesMock]}>
        <MarketplacePage />
      </MockedProvider>
    </MemoryRouter>
  );
}

describe('MarketplacePage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('renders skeletons while loading', () => {
    vi.mocked(useProductSearch).mockReturnValue(baseHookReturn({ loading: true }));
    renderPage();
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0);
  });

  it('renders the product grid once data arrives', () => {
    vi.mocked(useProductSearch).mockReturnValue(
      baseHookReturn({ products: [makeProduct()], totalElements: 1 })
    );
    renderPage();
    expect(screen.getByText('Sample Book')).toBeInTheDocument();
  });

  it('shows the empty state when there are no results', () => {
    vi.mocked(useProductSearch).mockReturnValue(baseHookReturn());
    renderPage();
    expect(screen.getByText(/No products found/i)).toBeInTheDocument();
  });

  it('shows the error state and retries on click', async () => {
    const refetch = vi.fn();
    vi.mocked(useProductSearch).mockReturnValue(
      baseHookReturn({ error: new Error('boom') as unknown as ApolloError, refetch })
    );
    renderPage();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Retry'));
    expect(refetch).toHaveBeenCalled();
  });

  it('calls setSearchQuery as the user types', async () => {
    const setSearchQuery = vi.fn();
    vi.mocked(useProductSearch).mockReturnValue(baseHookReturn({ setSearchQuery }));
    renderPage();
    fireEvent.change(screen.getByLabelText(/Search products/i), { target: { value: 'a' } });
    await waitFor(() => expect(setSearchQuery).toHaveBeenCalledWith('a'));
  });

  it('paginates to the next page', () => {
    const setPage = vi.fn();
    vi.mocked(useProductSearch).mockReturnValue(
      baseHookReturn({ products: [makeProduct()], totalElements: 30, totalPages: 2, pageNumber: 0, setPage })
    );
    renderPage();
    fireEvent.click(screen.getByText('Next'));
    expect(setPage).toHaveBeenCalledWith(1);
  });
});
