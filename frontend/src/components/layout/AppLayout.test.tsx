import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import AppLayout from './AppLayout';
import { useAuthStore } from '../../store/authStore';

vi.mock('../../store/authStore');
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('./NotificationBell', () => ({ default: () => null }));

function mockAuth(overrides: Record<string, unknown>) {
  vi.mocked(useAuthStore).mockReturnValue({
    isAuthenticated: true,
    user: { id: '1', displayName: 'Test User', roles: ['BUYER'] },
    ...overrides,
  } as ReturnType<typeof useAuthStore>);
}

function renderLayout() {
  render(
    <MemoryRouter>
      <AppLayout>
        <div>content</div>
      </AppLayout>
    </MemoryRouter>
  );
}

describe('AppLayout admin nav (Phase 8, acceptance criterion 9)', () => {
  it('hides the Admin link for a non-admin user', () => {
    mockAuth({ user: { id: '1', displayName: 'Bo Buyer', roles: ['BUYER'] } });
    renderLayout();

    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  });

  it('hides the Admin link for a creator without the ADMIN role', () => {
    mockAuth({ user: { id: '1', displayName: 'Casey Creator', roles: ['BUYER', 'CREATOR'] } });
    renderLayout();

    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  });

  it('shows the Admin link for a user with the ADMIN role', () => {
    mockAuth({ user: { id: '1', displayName: 'Ada Admin', roles: ['BUYER', 'ADMIN'] } });
    renderLayout();

    expect(screen.getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin');
  });

  it('hides the Admin link entirely when unauthenticated', () => {
    mockAuth({ isAuthenticated: false, user: null });
    renderLayout();

    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  });
});
