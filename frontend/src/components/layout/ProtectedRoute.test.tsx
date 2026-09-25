import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import ProtectedRoute from './ProtectedRoute';
import { useAuthStore } from '../../store/authStore';

// Mock the zustand store
vi.mock('../../store/authStore');

describe('ProtectedRoute', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  const TestApp = ({ requiredRole }: { requiredRole?: import('../../types').UserRole }) => (
    <MemoryRouter initialEntries={['/protected']}>
      <Routes>
        <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
        <Route path="/" element={<div data-testid="home-page">Home Page</div>} />
        <Route path="/become-creator" element={<div data-testid="become-creator-page">Become Creator</div>} />
        <Route
          path="/protected"
          element={
            <ProtectedRoute requiredRole={requiredRole}>
              <div data-testid="protected-content">Protected Content</div>
            </ProtectedRoute>
          }
        />
      </Routes>
    </MemoryRouter>
  );

  it('redirects to /login if not authenticated', () => {
    vi.mocked(useAuthStore).mockReturnValue({
      isAuthenticated: false,
      user: null,
    } as ReturnType<typeof useAuthStore>);

    render(<TestApp />);
    expect(screen.getByTestId('login-page')).toBeInTheDocument();
    expect(screen.queryByTestId('protected-content')).not.toBeInTheDocument();
  });

  it('renders children if authenticated and no role required', () => {
    vi.mocked(useAuthStore).mockReturnValue({
      isAuthenticated: true,
      user: { id: '1', roles: ['BUYER'] },
    } as ReturnType<typeof useAuthStore>);

    render(<TestApp />);
    expect(screen.getByTestId('protected-content')).toBeInTheDocument();
  });

  it('redirects to /become-creator if CREATOR role is required but missing', () => {
    vi.mocked(useAuthStore).mockReturnValue({
      isAuthenticated: true,
      user: { id: '1', roles: ['BUYER'] }, // missing CREATOR
    } as ReturnType<typeof useAuthStore>);

    render(<TestApp requiredRole="CREATOR" />);
    expect(screen.getByTestId('become-creator-page')).toBeInTheDocument();
    expect(screen.queryByTestId('protected-content')).not.toBeInTheDocument();
  });

  it('renders children if CREATOR role is required and present', () => {
    vi.mocked(useAuthStore).mockReturnValue({
      isAuthenticated: true,
      user: { id: '1', roles: ['BUYER', 'CREATOR'] },
    } as ReturnType<typeof useAuthStore>);

    render(<TestApp requiredRole="CREATOR" />);
    expect(screen.getByTestId('protected-content')).toBeInTheDocument();
  });
});
