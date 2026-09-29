import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import StatementsTab from './StatementsTab';
import { useCreatorStatement } from '../../hooks/useCreatorStatement';
import restClient from '../../lib/restClient';
import type { CreatorStatement } from '../../types';

vi.mock('../../hooks/useCreatorStatement');
vi.mock('../../lib/restClient', () => ({ default: { get: vi.fn() } }));

const statement: CreatorStatement = {
  month: '2026-09',
  grossSalesPaise: 170_000,
  refundsPaise: 20_000,
  platformFeePaise: 15_000,
  netEarningsPaise: 135_000,
  payoutsPaise: 30_000,
  lines: [
    { date: '2026-09-03T10:00:00+05:30', type: 'SALE', description: 'Order #1 — Paid Guide', grossPaise: 100_000, feePaise: 10_000, netPaise: 90_000 },
    { date: '2026-09-09T10:00:00+05:30', type: 'REFUND', description: 'Refund of order #3 — Old Guide', grossPaise: -20_000, feePaise: -2_000, netPaise: -18_000 },
  ],
};

beforeEach(() => {
  vi.mocked(useCreatorStatement).mockReturnValue({ statement, loading: false, error: undefined });
});

describe('StatementsTab', () => {
  it('shows the month totals and every line', () => {
    render(<StatementsTab />);
    expect(screen.getByText('Gross sales').nextSibling).toHaveTextContent('₹1,700');
    expect(screen.getByText('Refunds').nextSibling).toHaveTextContent('−₹200');
    expect(screen.getByText('Net earnings').nextSibling).toHaveTextContent('₹1,350');
    expect(screen.getByText('Paid out').nextSibling).toHaveTextContent('−₹300');
    expect(screen.getByText('Order #1 — Paid Guide')).toBeInTheDocument();
    expect(screen.getByText('Refund of order #3 — Old Guide')).toBeInTheDocument();
  });

  it('asks for a different month when the picker changes', () => {
    render(<StatementsTab />);
    fireEvent.change(screen.getByLabelText('Month'), { target: { value: '2026-08' } });
    expect(useCreatorStatement).toHaveBeenLastCalledWith('2026-08');
  });

  it('says so when a month has no activity', () => {
    vi.mocked(useCreatorStatement).mockReturnValue({ statement: { ...statement, lines: [] }, loading: false, error: undefined });
    render(<StatementsTab />);
    expect(screen.getByText(/no activity in 2026-09/i)).toBeInTheDocument();
  });

  it('downloads the CSV from the owner-only REST endpoint', async () => {
    vi.mocked(restClient.get).mockResolvedValue({ data: new Blob(['a,b']) });
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    render(<StatementsTab />);
    fireEvent.change(screen.getByLabelText('Month'), { target: { value: '2026-09' } });
    fireEvent.click(screen.getByRole('button', { name: 'Download CSV' }));
    await waitFor(() => expect(restClient.get).toHaveBeenCalledWith('/creator/statements/2026-09.csv', { responseType: 'blob' }));
  });

  it('reports a failed download', async () => {
    vi.mocked(restClient.get).mockRejectedValue(new Error('403'));
    render(<StatementsTab />);
    fireEvent.click(screen.getByRole('button', { name: 'Download CSV' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not download/i);
  });
});
