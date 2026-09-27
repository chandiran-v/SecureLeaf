import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import AdminProductsPage from './AdminProductsPage';
import { useAdminProducts } from '../../hooks/useAdminProducts';
import type { AdminProduct } from '../../types';

vi.mock('../../hooks/useAdminProducts');
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));

function makeProduct(overrides: Partial<AdminProduct> = {}): AdminProduct {
  return {
    id: '1',
    title: 'Sample Book',
    status: 'LIVE',
    creator: { id: 'c1', displayName: 'Casey Creator' },
    pricePaise: 1999,
    totalSales: 3,
    takenDownAt: null,
    takedownReason: null,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

function baseHookReturn(overrides: Partial<ReturnType<typeof useAdminProducts>> = {}) {
  return {
    search: '',
    status: undefined,
    products: [],
    totalElements: 0,
    totalPages: 0,
    pageNumber: 0,
    loading: false,
    error: undefined,
    refetch: vi.fn(),
    takeDownProduct: vi.fn(),
    takeDownLoading: false,
    restoreProduct: vi.fn(),
    restoreLoading: false,
    setSearch: vi.fn(),
    setStatus: vi.fn(),
    setPage: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof useAdminProducts>;
}

function renderPage(overrides: Partial<ReturnType<typeof useAdminProducts>> = {}) {
  vi.mocked(useAdminProducts).mockReturnValue(baseHookReturn(overrides));
  render(
    <MemoryRouter>
      <AdminProductsPage />
    </MemoryRouter>
  );
}

describe('AdminProductsPage', () => {
  it('shows a Take down button for a LIVE product', () => {
    renderPage({ products: [makeProduct()] });
    expect(screen.getByRole('button', { name: /take down/i })).toBeInTheDocument();
  });

  it('shows the reason and a Restore button for an admin-taken-down product, no Take down button', () => {
    renderPage({
      products: [makeProduct({ status: 'UNPUBLISHED', takedownReason: 'Copyright complaint' })],
    });
    expect(screen.getByText(/copyright complaint/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /restore/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /take down/i })).not.toBeInTheDocument();
  });

  it('a creator-unpublished product (no takedownReason) offers neither Take down nor Restore', () => {
    renderPage({ products: [makeProduct({ status: 'UNPUBLISHED', takedownReason: null })] });
    expect(screen.queryByRole('button', { name: /take down/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /restore/i })).not.toBeInTheDocument();
  });

  // Phase 8 acceptance criterion 9: the take-down modal requires a reason.
  it('the take-down modal requires a non-empty reason before Confirm is enabled', async () => {
    const takeDownProduct = vi.fn().mockResolvedValue(undefined);
    renderPage({ products: [makeProduct()], takeDownProduct });

    fireEvent.click(screen.getByRole('button', { name: /take down/i }));

    const dialog = await screen.findByRole('alertdialog');
    const confirmButton = within(dialog).getByRole('button', { name: /^take down$/i });
    expect(confirmButton).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText(/reason/i), { target: { value: '  ' } });
    expect(confirmButton).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText(/reason/i), { target: { value: 'Copyright complaint' } });
    expect(confirmButton).toBeEnabled();

    fireEvent.click(confirmButton);
    await waitFor(() => expect(takeDownProduct).toHaveBeenCalledWith('1', 'Copyright complaint'));
  });

  it('cancelling the take-down modal never calls takeDownProduct', () => {
    const takeDownProduct = vi.fn();
    renderPage({ products: [makeProduct()], takeDownProduct });

    fireEvent.click(screen.getByRole('button', { name: /take down/i }));
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(takeDownProduct).not.toHaveBeenCalled();
  });
});
