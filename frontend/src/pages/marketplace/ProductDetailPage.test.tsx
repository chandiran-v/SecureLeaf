import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MockedProvider, type MockedResponse } from '@apollo/client/testing';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ProductDetailPage from './ProductDetailPage';
import { PRODUCT_DETAIL } from '../../graphql/queries/marketplace.queries';
import { PRODUCT_REVIEWS, RATING_BREAKDOWN, MY_REVIEW } from '../../graphql/queries/review.queries';
import { useAuthStore } from '../../store/authStore';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));
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
  ownedByMe: false,
};

function detailMock(overrides: Partial<typeof product> = {}): MockedResponse {
  return {
    request: { query: PRODUCT_DETAIL, variables: { id: '1' } },
    result: { data: { product: { ...product, ...overrides } } },
  };
}

function reviewMocks(ownedByMe = false): MockedResponse[] {
  const mocks: MockedResponse[] = [
    {
      request: { query: PRODUCT_REVIEWS, variables: { productId: '1', page: 0, size: 10 } },
      result: { data: { productReviews: { content: [], totalElements: 0, totalPages: 0, pageNumber: 0 } } },
    },
    {
      request: { query: RATING_BREAKDOWN, variables: { productId: '1' } },
      result: { data: { ratingBreakdown: [5, 4, 3, 2, 1].map((rating) => ({ rating, count: 0 })) } },
    },
  ];
  if (ownedByMe) {
    mocks.push({
      request: { query: MY_REVIEW, variables: { productId: '1' } },
      result: { data: { myReview: null } },
    });
  }
  return mocks;
}

function renderDetail(mocks: MockedResponse[], ownedByMe = false) {
  render(
    <MemoryRouter initialEntries={['/product/1']}>
      <MockedProvider mocks={[...mocks, ...reviewMocks(ownedByMe)]}>
        <Routes>
          <Route path="/product/:id" element={<ProductDetailPage />} />
          <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
        </Routes>
      </MockedProvider>
    </MemoryRouter>
  );
}

function logInAs(id: string) {
  useAuthStore.setState({
    isAuthenticated: true,
    accessToken: 't',
    refreshToken: 'r',
    user: { id, email: `${id}@x.com`, displayName: id, roles: ['BUYER'], createdAt: '' },
  });
}

describe('ProductDetailPage — buy panel states', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: false, user: null, accessToken: null, refreshToken: null });
  });

  it('asks an anonymous visitor to log in', async () => {
    renderDetail([detailMock()]);
    await waitFor(() => expect(screen.getByText('Detail Book')).toBeInTheDocument());

    expect(screen.getByRole('button', { name: /log in to buy/i })).toBeEnabled();
    expect(screen.getByText(/Jane/)).toBeInTheDocument();
  });

  it('pops up a sign-in prompt when a guest clicks "Log in to buy", and Sign in goes to /login', async () => {
    renderDetail([detailMock()]);
    await waitFor(() => expect(screen.getByText('Detail Book')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /log in to buy/i }));
    const dialog = screen.getByRole('dialog', { name: /sign in to continue/i });
    expect(dialog).toHaveTextContent(/to buy this book/i);

    fireEvent.click(within(dialog).getByRole('button', { name: /^sign in$/i }));
    expect(screen.getByTestId('login-page')).toBeInTheDocument();
  });

  it('"Not now" closes the prompt and leaves the guest on the product page', async () => {
    renderDetail([detailMock()]);
    await waitFor(() => expect(screen.getByText('Detail Book')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /log in to buy/i }));
    fireEvent.click(screen.getByRole('button', { name: /not now/i }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText('Detail Book')).toBeInTheDocument();
  });

  it('offers a guest "Keep reading" at the end of the free preview, which asks them to sign in', async () => {
    renderDetail([detailMock()]); // 3 free preview pages of 10
    await waitFor(() => expect(screen.getByText('Detail Book')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /keep reading/i }));
    expect(screen.getByRole('dialog', { name: /sign in to continue/i })).toHaveTextContent(/end of the free preview/i);
  });

  it('does not offer "Keep reading" when the preview already covers the whole book', async () => {
    renderDetail([detailMock({ freePreviewPages: 1, pageCount: 1 })]);
    await waitFor(() => expect(screen.getByText('Detail Book')).toBeInTheDocument());

    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /keep reading/i })).not.toBeInTheDocument();
  });

  it('does not offer a signed-in user "Keep reading" (they buy from the panel instead)', async () => {
    logInAs('buyer1');
    renderDetail([detailMock({ freePreviewPages: 1 })]);
    await waitFor(() => expect(screen.getByText('Detail Book')).toBeInTheDocument());

    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /keep reading/i })).not.toBeInTheDocument();
  });

  it('shows an enabled Buy button with the price to a logged-in buyer', async () => {
    logInAs('buyer1');
    renderDetail([detailMock()]);

    await waitFor(() => expect(screen.getByRole('button', { name: /buy for ₹49\.99/i })).toBeEnabled());
  });

  it('offers a free product as "Get for free"', async () => {
    logInAs('buyer1');
    renderDetail([detailMock({ pricePaise: 0 })]);

    await waitFor(() => expect(screen.getByRole('button', { name: /get for free/i })).toBeEnabled());
  });

  it('links to the secure reader instead of Buy when the product is already owned', async () => {
    logInAs('buyer1');
    renderDetail([detailMock({ ownedByMe: true })], true);

    await waitFor(() => expect(screen.getByRole('link', { name: /read now/i })).toHaveAttribute('href', '/read/1'));
    expect(screen.queryByRole('button', { name: /buy/i })).not.toBeInTheDocument();
  });

  it("disables buying for the product's own creator", async () => {
    logInAs('c1');
    renderDetail([detailMock()]);

    await waitFor(() => expect(screen.getByRole('button', { name: /this is your product/i })).toBeDisabled());
  });

  it('shows a not-found state when the product does not exist', async () => {
    renderDetail([{ request: { query: PRODUCT_DETAIL, variables: { id: '1' } }, result: { data: { product: null } } }]);

    await waitFor(() => expect(screen.getByText(/Product not found/i)).toBeInTheDocument());
  });
});
