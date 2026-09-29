import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import AdminPayoutsPage from './AdminPayoutsPage';
import { useAdminPayouts } from '../../hooks/useAdminPayouts';
import type { Payout } from '../../types';

vi.mock('../../hooks/useAdminPayouts');
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));

function makePayout(overrides: Partial<Payout> = {}): Payout {
  return {
    id: '5',
    creatorId: 'c1',
    creatorName: 'Casey Creator',
    creatorEmail: 'casey@example.com',
    amountPaise: 50_000,
    status: 'REQUESTED',
    grossRevenuePaise: 0,
    platformFeePaise: 0,
    netPayoutPaise: 50_000,
    payoutMethod: 'UPI',
    payoutDestination: 'casey@upi',
    requestedAt: '2026-09-20T10:00:00Z',
    ...overrides,
  };
}

function renderPage(payouts: Payout[]) {
  const actions = {
    approvePayout: vi.fn().mockResolvedValue(undefined),
    markPayoutPaid: vi.fn().mockResolvedValue(undefined),
    rejectPayout: vi.fn().mockResolvedValue(undefined),
    setStatus: vi.fn(),
    setPage: vi.fn(),
  };
  vi.mocked(useAdminPayouts).mockReturnValue({
    status: undefined, payouts, totalPages: 1, pageNumber: 0, loading: false, error: undefined,
    refetch: vi.fn(), busy: false, ...actions,
  } as unknown as ReturnType<typeof useAdminPayouts>);
  render(<MemoryRouter><AdminPayoutsPage /></MemoryRouter>);
  return actions;
}

describe('AdminPayoutsPage actions', () => {
  it('shows where to send the money and offers Approve / Reject on a REQUESTED payout', () => {
    renderPage([makePayout()]);
    expect(screen.getByText('casey@upi')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mark paid' })).not.toBeInTheDocument();
  });

  it('Approve calls the mutation with the payout id', () => {
    const actions = renderPage([makePayout()]);
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(actions.approvePayout).toHaveBeenCalledWith('5');
  });

  it('Mark paid needs a transfer reference before Confirm is enabled', () => {
    const actions = renderPage([makePayout({ status: 'APPROVED' })]);
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Mark paid' }));

    const dialog = screen.getByRole('alertdialog');
    const confirm = screen.getAllByRole('button', { name: 'Mark paid' }).find((b) => dialog.contains(b))!;
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Transfer reference'), { target: { value: 'UTR-998877' } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    expect(actions.markPayoutPaid).toHaveBeenCalledWith('5', 'UTR-998877');
  });

  it('Reject needs a reason', () => {
    const actions = renderPage([makePayout()]);
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }));
    const dialog = screen.getByRole('alertdialog');
    const confirm = screen.getAllByRole('button', { name: 'Reject' }).find((b) => dialog.contains(b))!;
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'Bad UPI id' } });
    fireEvent.click(confirm);
    expect(actions.rejectPayout).toHaveBeenCalledWith('5', 'Bad UPI id');
  });

  it('offers no actions on a PAID payout and shows its reference', () => {
    renderPage([makePayout({ status: 'PAID', payoutReference: 'UTR-1' })]);
    expect(screen.queryByRole('button', { name: /approve|mark paid|reject/i })).not.toBeInTheDocument();
    expect(screen.getByText('Ref UTR-1')).toBeInTheDocument();
  });
});
