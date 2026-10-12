import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import HomeRoute from './HomeRoute';
import { useAuthStore } from '../../store/authStore';

vi.mock('../../pages/marketplace/MarketplacePage', () => ({
  default: () => <div data-testid="marketplace-page">Marketplace</div>,
}));

function renderHome() {
  render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<HomeRoute />} />
        <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('HomeRoute', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: false, user: null, accessToken: null, refreshToken: null });
  });

  it('sends a logged-out visitor to the login page', () => {
    renderHome();
    expect(screen.getByTestId('login-page')).toBeInTheDocument();
    expect(screen.queryByTestId('marketplace-page')).not.toBeInTheDocument();
  });

  it('shows the marketplace to a signed-in user', () => {
    useAuthStore.setState({
      isAuthenticated: true,
      accessToken: 't',
      refreshToken: 'r',
      user: { id: 'u1', email: 'u1@x.com', displayName: 'u1', roles: ['BUYER'], createdAt: '' },
    });
    renderHome();
    expect(screen.getByTestId('marketplace-page')).toBeInTheDocument();
  });
});
