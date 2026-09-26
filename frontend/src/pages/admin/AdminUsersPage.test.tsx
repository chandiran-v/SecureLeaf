import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import AdminUsersPage from './AdminUsersPage';
import { useAdminUsers } from '../../hooks/useAdminUsers';
import type { AdminUser } from '../../types';

vi.mock('../../hooks/useAdminUsers');
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));

function makeUser(overrides: Partial<AdminUser> = {}): AdminUser {
  return {
    id: '1',
    email: 'buyer@example.com',
    displayName: 'Bo Buyer',
    roles: ['BUYER'],
    accountStatus: 'ACTIVE',
    authProvider: 'LOCAL',
    createdAt: new Date().toISOString(),
    productCount: 0,
    purchaseCount: 2,
    ...overrides,
  };
}

function baseHookReturn(overrides: Partial<ReturnType<typeof useAdminUsers>> = {}) {
  return {
    search: '',
    role: undefined,
    status: undefined,
    users: [],
    totalElements: 0,
    totalPages: 0,
    pageNumber: 0,
    loading: false,
    error: undefined,
    refetch: vi.fn(),
    suspendUser: vi.fn(),
    suspendLoading: false,
    reactivateUser: vi.fn(),
    reactivateLoading: false,
    setSearch: vi.fn(),
    setRole: vi.fn(),
    setStatus: vi.fn(),
    setPage: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof useAdminUsers>;
}

function renderPage(overrides: Partial<ReturnType<typeof useAdminUsers>> = {}) {
  vi.mocked(useAdminUsers).mockReturnValue(baseHookReturn(overrides));
  render(
    <MemoryRouter>
      <AdminUsersPage />
    </MemoryRouter>
  );
}

describe('AdminUsersPage', () => {
  it('shows a Suspend button for an active, non-admin user', () => {
    renderPage({ users: [makeUser()] });
    expect(screen.getByRole('button', { name: /suspend/i })).toBeInTheDocument();
  });

  it('hides the Suspend button for an admin', () => {
    renderPage({ users: [makeUser({ roles: ['BUYER', 'ADMIN'] })] });
    expect(screen.queryByRole('button', { name: /suspend/i })).not.toBeInTheDocument();
  });

  it('shows Reactivate instead of Suspend for a suspended user', () => {
    renderPage({ users: [makeUser({ accountStatus: 'SUSPENDED' })] });
    expect(screen.getByRole('button', { name: /reactivate/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /suspend/i })).not.toBeInTheDocument();
  });

  // Phase 8 acceptance criterion 9: the suspend modal requires a reason.
  it('the suspend modal requires a non-empty reason before Confirm is enabled', async () => {
    const suspendUser = vi.fn().mockResolvedValue(undefined);
    renderPage({ users: [makeUser()], suspendUser });

    fireEvent.click(screen.getByRole('button', { name: /suspend/i }));

    const dialog = await screen.findByRole('alertdialog');
    const confirmButton = within(dialog).getByRole('button', { name: /^suspend$/i });
    expect(confirmButton).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText(/reason/i), { target: { value: '   ' } });
    expect(confirmButton).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText(/reason/i), { target: { value: 'Policy violation' } });
    expect(confirmButton).toBeEnabled();

    fireEvent.click(confirmButton);
    await waitFor(() => expect(suspendUser).toHaveBeenCalledWith('1', 'Policy violation'));
  });

  it('cancelling the suspend modal never calls suspendUser', () => {
    const suspendUser = vi.fn();
    renderPage({ users: [makeUser()], suspendUser });

    fireEvent.click(screen.getByRole('button', { name: /suspend/i }));
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(suspendUser).not.toHaveBeenCalled();
  });
});
