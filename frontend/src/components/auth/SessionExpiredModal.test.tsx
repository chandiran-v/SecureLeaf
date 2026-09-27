import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, it, expect, beforeEach } from 'vitest';
import SessionExpiredModal from './SessionExpiredModal';
import { useSessionStore } from '../../store/sessionStore';
import { useAuthStore } from '../../store/authStore';

function LoginProbe() {
  const location = useLocation();
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname;
  return <div>Login page (from {from ?? 'nowhere'})</div>;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<LoginProbe />} />
        <Route path="/marketplace" element={<div>Marketplace page</div>} />
        <Route path="*" element={<div>Some page</div>} />
      </Routes>
      <SessionExpiredModal />
    </MemoryRouter>
  );
}

describe('SessionExpiredModal', () => {
  beforeEach(() => {
    useSessionStore.setState({ endedReason: null });
    useAuthStore.getState().clearAuth();
  });

  it('stays hidden while the session is fine', () => {
    renderAt('/library');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('explains an expired session in an accessible dialog, focused on its first button', () => {
    renderAt('/library');
    act(() => useSessionStore.setState({ endedReason: 'expired' }));

    const dialog = screen.getByRole('dialog', { name: 'Your session has expired' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleDescription(/signed out after a period of inactivity/i);
    expect(screen.getByRole('button', { name: 'Continue browsing' })).toHaveFocus();
  });

  it('uses different wording for an invalid session', () => {
    renderAt('/library');
    act(() => useSessionStore.setState({ endedReason: 'invalid' }));

    expect(screen.getByRole('dialog', { name: 'Your session is no longer valid' })).toBeInTheDocument();
  });

  it('"Log in again" goes to /login remembering the page to return to', () => {
    renderAt('/creator');
    act(() => useSessionStore.setState({ endedReason: 'expired' }));

    fireEvent.click(screen.getByRole('button', { name: 'Log in again' }));

    expect(screen.getByText('Login page (from /creator)')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('"Continue browsing" closes it and goes to the marketplace', () => {
    renderAt('/library');
    act(() => useSessionStore.setState({ endedReason: 'expired' }));

    fireEvent.click(screen.getByRole('button', { name: 'Continue browsing' }));

    expect(screen.getByText('Marketplace page')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('cannot be dismissed with Escape: the user must pick an action', () => {
    renderAt('/library');
    act(() => useSessionStore.setState({ endedReason: 'expired' }));

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('endSession signs the user out and opens the modal only if they were signed in', () => {
    renderAt('/library');
    act(() => useSessionStore.getState().endSession('expired'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); // was a visitor

    act(() => {
      useAuthStore.getState().setAuth(
        { id: '1', email: 'u@x.com', displayName: 'U', roles: ['BUYER'], createdAt: '' },
        'a',
        'r'
      );
      useSessionStore.getState().endSession('expired');
    });
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
