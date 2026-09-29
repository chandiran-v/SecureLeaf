import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import SiteFooter from './SiteFooter';
import AppLayout from './AppLayout';
import { useAuthStore } from '../../store/authStore';

vi.mock('../../store/authStore');
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('./NotificationBell', () => ({ default: () => null }));

const EXPECTED = [
  ['Terms', '/terms'],
  ['Privacy', '/privacy'],
  ['Refund policy', '/refund-policy'],
  ['Contact', '/contact'],
  ['About', '/about'],
];

describe('SiteFooter (Phase 09C D6)', () => {
  it('links to every legal and info page', () => {
    render(<MemoryRouter><SiteFooter /></MemoryRouter>);
    for (const [label, href] of EXPECTED) {
      expect(screen.getByRole('link', { name: label })).toHaveAttribute('href', href);
    }
  });

  it('is present on every page that uses the app layout, and hidden from print', () => {
    vi.mocked(useAuthStore).mockReturnValue({ isAuthenticated: false, user: null } as ReturnType<typeof useAuthStore>);
    render(<MemoryRouter><AppLayout><p>page</p></AppLayout></MemoryRouter>);
    const legal = screen.getByRole('navigation', { name: 'Legal' });
    expect(legal).toBeInTheDocument();
    expect(legal.closest('footer')).toHaveClass('no-print');
  });
});
