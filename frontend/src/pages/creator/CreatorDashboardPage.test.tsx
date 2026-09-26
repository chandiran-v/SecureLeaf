import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MockedProvider } from '@apollo/client/testing';
import { describe, it, expect, vi } from 'vitest';
import CreatorDashboardPage from './CreatorDashboardPage';
import { MY_PRODUCTS } from '../../graphql/queries/product.queries';
import { CREATOR_EARNINGS } from '../../graphql/queries/commerce.queries';
import { RETRY_PROCESSING, UNPUBLISH_PRODUCT } from '../../graphql/mutations/product.mutations';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));

function makeProduct(overrides: Record<string, unknown> = {}) {
  return {
    __typename: 'Product',
    id: '1',
    title: 'My Guide',
    description: 'desc',
    pricePaise: 1000,
    status: 'LIVE',
    creator: { __typename: 'CreatorSummary', id: 'c1', displayName: 'Casey' },
    category: { __typename: 'Category', id: 'g', name: 'Guides', slug: 'guides' },
    tags: [],
    thumbnailUrl: null,
    averageRating: null,
    totalSales: 3,
    freePreviewPages: 2,
    pageCount: 10,
    createdAt: '2026-09-01T00:00:00Z',
    salesCount: 3,
    netEarningsPaise: 2700,
    processingStage: null,
    failureReason: null,
    takedownReason: null,
    ...overrides,
  };
}

const earningsMock = {
  request: { query: CREATOR_EARNINGS },
  result: {
    data: {
      creatorEarnings: { salesCount: 3, grossSalesPaise: 3000, platformFeePaise: 300, netEarningsPaise: 2700 },
    },
  },
};

function myProductsMock(products: unknown[]) {
  return { request: { query: MY_PRODUCTS }, result: { data: { myProducts: products } } };
}

function renderDashboard(mocks: unknown[]) {
  render(
    <MemoryRouter>
      <MockedProvider mocks={mocks as never}>
        <CreatorDashboardPage />
      </MockedProvider>
    </MemoryRouter>
  );
}

describe('CreatorDashboardPage', () => {
  it('shows the earnings stat cards from creatorEarnings', async () => {
    renderDashboard([myProductsMock([makeProduct()]), earningsMock]);

    expect(await screen.findByText('My Guide')).toBeInTheDocument();
    expect(await screen.findByText('₹27')).toBeInTheDocument(); // "Your Earnings" = ₹2700 paise
    expect(screen.getByText(/platform fee/i)).toBeInTheDocument();
  });

  it('shows a FAILED product with its failure reason and a Retry button', async () => {
    const failed = makeProduct({
      id: '2',
      status: 'FAILED',
      failureReason: 'PDF is encrypted.',
      salesCount: 0,
      netEarningsPaise: 0,
    });
    renderDashboard([myProductsMock([failed]), earningsMock]);

    expect(await screen.findByTestId('status-badge-failed')).toBeInTheDocument();
    expect(screen.getByText('PDF is encrypted.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('clicking Retry on a FAILED product calls retryProcessing', async () => {
    const failed = makeProduct({ id: '2', status: 'FAILED', failureReason: 'boom' });
    let retryRequested = false;
    const retryMock = {
      request: { query: RETRY_PROCESSING, variables: { productId: '2' } },
      result: () => {
        retryRequested = true;
        return { data: { retryProcessing: makeProduct({ id: '2', status: 'PROCESSING' }) } };
      },
    };

    renderDashboard([myProductsMock([failed]), earningsMock, retryMock, myProductsMock([failed])]);

    const retryButton = await screen.findByRole('button', { name: /retry/i });
    fireEvent.click(retryButton);

    await waitFor(() => expect(retryRequested).toBe(true));
  });

  it('shows a Republish button for an UNPUBLISHED product', async () => {
    const unpublished = makeProduct({ id: '3', status: 'UNPUBLISHED' });
    renderDashboard([myProductsMock([unpublished]), earningsMock]);

    expect(await screen.findByTestId('status-badge-unpublished')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /republish/i })).toBeInTheDocument();
  });

  it('shows an admin takedown reason and hides the Republish button', async () => {
    const takenDown = makeProduct({
      id: '3b',
      status: 'UNPUBLISHED',
      takedownReason: 'Copyright complaint',
    });
    renderDashboard([myProductsMock([takenDown]), earningsMock]);

    expect(await screen.findByTestId('status-badge-unpublished')).toBeInTheDocument();
    expect(screen.getByText(/copyright complaint/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /republish/i })).not.toBeInTheDocument();
  });

  it('unpublishing a LIVE product asks for confirmation before mutating', async () => {
    const live = makeProduct({ id: '4', status: 'LIVE' });
    let unpublishCalled = false;
    const unpublishMock = {
      request: { query: UNPUBLISH_PRODUCT, variables: { id: '4' } },
      result: () => {
        unpublishCalled = true;
        return { data: { unpublishProduct: makeProduct({ id: '4', status: 'UNPUBLISHED' }) } };
      },
    };

    renderDashboard([myProductsMock([live]), earningsMock, unpublishMock, myProductsMock([live])]);

    fireEvent.click(await screen.findByRole('button', { name: /^unpublish$/i }));

    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByText(/buyers who already own it keep their access/i)).toBeInTheDocument();

    // Not called yet — only the confirmation dialog is open.
    expect(unpublishCalled).toBe(false);

    fireEvent.click(within(dialog).getByRole('button', { name: /^unpublish$/i }));

    await waitFor(() => expect(unpublishCalled).toBe(true));
  });

  it('cancelling the confirm dialog never calls the mutation', async () => {
    const live = makeProduct({ id: '5', status: 'LIVE' });
    renderDashboard([myProductsMock([live]), earningsMock]);

    fireEvent.click(await screen.findByRole('button', { name: /^unpublish$/i }));
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /cancel/i }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });
});
