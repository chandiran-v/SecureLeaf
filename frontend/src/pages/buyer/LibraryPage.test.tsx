import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MockedProvider } from '@apollo/client/testing';
import { describe, it, expect, vi } from 'vitest';
import LibraryPage from './LibraryPage';
import { MY_LIBRARY } from '../../graphql/queries/commerce.queries';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));

function renderLibrary(myLibrary: unknown[]) {
  render(
    <MemoryRouter>
      <MockedProvider mocks={[{ request: { query: MY_LIBRARY }, result: { data: { myLibrary } } }]}>
        <LibraryPage />
      </MockedProvider>
    </MemoryRouter>
  );
}

describe('LibraryPage', () => {
  it('lists owned products with a (disabled until Phase 5) Read button', async () => {
    renderLibrary([
      {
        __typename: 'Entitlement',
        id: '1',
        purchasedAt: '2026-09-20T10:00:00Z',
        status: 'ACTIVE',
        product: {
          __typename: 'Product',
          id: '7',
          title: 'Paid Guide',
          thumbnailUrl: null,
          pageCount: 10,
          creator: { __typename: 'CreatorSummary', id: 'c1', displayName: 'Casey' },
          category: { __typename: 'Category', id: 'g', name: 'Guides', slug: 'guides' },
        },
      },
    ]);

    expect(await screen.findByText('Paid Guide')).toBeInTheDocument();
    expect(screen.getByText(/by Casey/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /read/i })).toBeDisabled();
  });

  it('shows an empty state with a way back to the marketplace', async () => {
    renderLibrary([]);

    expect(await screen.findByText(/your library is empty/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /browse the marketplace/i })).toHaveAttribute('href', '/marketplace');
  });
});
