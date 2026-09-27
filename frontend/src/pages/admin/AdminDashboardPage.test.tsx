import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import AdminDashboardPage from './AdminDashboardPage';
import { useAdminAnalytics } from '../../hooks/useAdminAnalytics';

vi.mock('../../hooks/useAdminAnalytics');
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));

describe('AdminDashboardPage', () => {
  it('renders stat cards and the top products table from platformStats', () => {
    vi.mocked(useAdminAnalytics).mockReturnValue({
      stats: {
        totalUsers: 42,
        totalCreators: 5,
        totalLiveProducts: 12,
        completedOrdersAllTime: 100,
        grossSalesPaiseAllTime: 5_000_00,
        platformFeePaiseAllTime: 500_00,
        windowDays: 30,
        completedOrdersWindow: 10,
        grossSalesPaiseWindow: 1_000_00,
        platformFeePaiseWindow: 100_00,
        topProducts: [{ productId: '1', title: 'Best Seller', salesCount: 7, grossSalesPaise: 700_00 }],
      },
      loading: false,
      error: undefined,
      days: 30,
      setDays: vi.fn(),
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useAdminAnalytics>);

    render(
      <MemoryRouter>
        <AdminDashboardPage />
      </MemoryRouter>
    );

    expect(screen.getByText('42')).toBeInTheDocument(); // Total Users
    expect(screen.getByText('Best Seller')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '7 days' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '30 days' })).toHaveAttribute('aria-pressed', 'true');
  });
});
