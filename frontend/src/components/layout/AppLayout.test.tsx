import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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

describe('AppLayout mobile menu', () => {
  function renderWithRoutes() {
    render(
      <MemoryRouter initialEntries={['/marketplace']}>
        <Routes>
          <Route path="*" element={<AppLayout><div>content</div></AppLayout>} />
        </Routes>
      </MemoryRouter>
    );
  }

  function openMenu() {
    fireEvent.click(screen.getByRole('button', { name: 'Open menu' }));
    return screen.getByRole('navigation', { name: 'Mobile' });
  }

  it('is closed by default and opens from the menu button', () => {
    mockAuth({ user: { id: '1', displayName: 'Bo Buyer', roles: ['BUYER'] } });
    renderWithRoutes();

    expect(screen.queryByRole('navigation', { name: 'Mobile' })).not.toBeInTheDocument();
    const button = screen.getByRole('button', { name: 'Open menu' });
    expect(button).toHaveAttribute('aria-expanded', 'false');

    openMenu();

    expect(screen.getByRole('button', { name: 'Close menu' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('lists My Library and Creator Dashboard for a creator, plus their name and Log out', () => {
    mockAuth({ user: { id: '1', displayName: 'Casey Creator', roles: ['BUYER', 'CREATOR'] } });
    renderWithRoutes();

    const menu = openMenu();

    expect(within(menu).getByRole('link', { name: 'Marketplace' })).toHaveAttribute('href', '/marketplace');
    expect(within(menu).getByRole('link', { name: 'My Library' })).toHaveAttribute('href', '/library');
    expect(within(menu).getByRole('link', { name: 'Creator Dashboard' })).toHaveAttribute('href', '/creator');
    expect(within(menu).queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    expect(within(menu).getByText('Casey Creator')).toBeInTheDocument();
    expect(within(menu).getByRole('button', { name: /log out/i })).toBeInTheDocument();
  });

  it('offers "Become a Creator" to a buyer and "Admin" to an admin', () => {
    mockAuth({ user: { id: '1', displayName: 'Ada Admin', roles: ['BUYER', 'ADMIN'] } });
    renderWithRoutes();

    const menu = openMenu();

    expect(within(menu).getByRole('link', { name: 'Become a Creator' })).toHaveAttribute('href', '/become-creator');
    expect(within(menu).getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin');
  });

  it('shows only Marketplace to a logged-out visitor', () => {
    mockAuth({ isAuthenticated: false, user: null });
    renderWithRoutes();

    const menu = openMenu();

    expect(within(menu).getAllByRole('link').map((link) => link.textContent)).toEqual(['Marketplace']);
    expect(within(menu).queryByRole('button', { name: /log out/i })).not.toBeInTheDocument();
  });

  it('closes after tapping a link', () => {
    mockAuth({ user: { id: '1', displayName: 'Bo Buyer', roles: ['BUYER'] } });
    renderWithRoutes();

    fireEvent.click(within(openMenu()).getByRole('link', { name: 'My Library' }));

    expect(screen.queryByRole('navigation', { name: 'Mobile' })).not.toBeInTheDocument();
  });

  it('closes on Escape and when tapping outside it', () => {
    mockAuth({ user: { id: '1', displayName: 'Bo Buyer', roles: ['BUYER'] } });
    const { container } = render(
      <MemoryRouter>
        <AppLayout><div>content</div></AppLayout>
      </MemoryRouter>
    );

    openMenu();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('navigation', { name: 'Mobile' })).not.toBeInTheDocument();

    openMenu();
    fireEvent.click(container.querySelector('div[aria-hidden="true"].fixed') as Element);
    expect(screen.queryByRole('navigation', { name: 'Mobile' })).not.toBeInTheDocument();
  });
});
