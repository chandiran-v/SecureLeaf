import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MockedProvider } from '@apollo/client/testing';
import { describe, it, expect, vi } from 'vitest';
import LibraryPage from './LibraryPage';
import { MY_LIBRARY } from '../../graphql/queries/commerce.queries';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));

function entitlement(overrides: Record<string, unknown> = {}) {
  return {
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
    ...overrides,
  };
}

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
  it('lists owned products with a Read link into the secure reader', async () => {
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
    expect(screen.getByRole('link', { name: /read/i })).toHaveAttribute('href', '/read/7');
  });

  it('shows an empty state with a way back to the marketplace', async () => {
    renderLibrary([]);

    expect(await screen.findByText(/your library is empty/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /browse the marketplace/i })).toHaveAttribute('href', '/marketplace');
  });

  // Phase 6, D1 — every status shows up, and only ACTIVE can be read.
  it('shows a status badge and a disabled reason for a REVOKED entitlement', async () => {
    renderLibrary([entitlement({ id: '2', status: 'REVOKED' })]);

    expect(await screen.findByTestId('status-badge-revoked')).toBeInTheDocument();
    const disabledButton = screen.getByRole('button', { name: /access to this product was revoked/i });
    expect(disabledButton).toBeDisabled();
    expect(screen.queryByRole('link', { name: /read/i })).not.toBeInTheDocument();
  });

  it('shows a Read link and an Active badge for an ACTIVE entitlement', async () => {
    renderLibrary([entitlement()]);

    expect(await screen.findByTestId('status-badge-active')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /read/i })).toBeInTheDocument();
  });

  it('filters the library by title as the buyer types', async () => {
    renderLibrary([
      entitlement({ id: '1', product: { ...entitlement().product, id: '7', title: 'Paid Guide' } }),
      entitlement({ id: '2', product: { ...entitlement().product, id: '8', title: 'Cooking Basics' } }),
    ]);

    expect(await screen.findByText('Paid Guide')).toBeInTheDocument();
    expect(screen.getByText('Cooking Basics')).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText(/search your library/i), { target: { value: 'cooking' } });

    expect(screen.queryByText('Paid Guide')).not.toBeInTheDocument();
    expect(screen.getByText('Cooking Basics')).toBeInTheDocument();
  });
});
