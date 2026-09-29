import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import RequestPayoutForm from './RequestPayoutForm';

const balance = { availablePaise: 115_000, pendingPaise: 0, lifetimeEarningsPaise: 115_000, paidOutPaise: 0 };

function setup(overrides: Partial<React.ComponentProps<typeof RequestPayoutForm>> = {}) {
  const onSubmit = vi.fn().mockResolvedValue(undefined);
  render(
    <RequestPayoutForm balance={balance} hasOpenRequest={false} hasPayoutDetails submitting={false} onSubmit={onSubmit} {...overrides} />
  );
  return { onSubmit, input: screen.getByLabelText('Amount (₹)') };
}

describe('RequestPayoutForm validation', () => {
  it('submits the amount in integer paise when valid', async () => {
    const { onSubmit, input } = setup();
    fireEvent.change(input, { target: { value: '500.50' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request payout' }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(50_050));
  });

  it('refuses an amount below the ₹100 minimum', () => {
    const { onSubmit, input } = setup();
    fireEvent.change(input, { target: { value: '50' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request payout' }));
    expect(screen.getByRole('alert')).toHaveTextContent(/minimum payout is ₹100/i);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('refuses an amount above the available balance', () => {
    const { onSubmit, input } = setup();
    fireEvent.change(input, { target: { value: '2000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request payout' }));
    expect(screen.getByRole('alert')).toHaveTextContent(/more than your available balance/i);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('is disabled, with the reason, while another request is open', () => {
    setup({ hasOpenRequest: true });
    expect(screen.getByRole('button', { name: 'Request payout' })).toBeDisabled();
    expect(screen.getByText(/already have a payout in progress/i)).toBeInTheDocument();
  });

  it('asks for payout details first when there are none', () => {
    setup({ hasPayoutDetails: false });
    expect(screen.getByRole('button', { name: 'Request payout' })).toBeDisabled();
    expect(screen.getByText(/add your upi id or email/i)).toBeInTheDocument();
  });

  it('shows the server\'s message if the request is refused', async () => {
    const { input } = setup({ onSubmit: vi.fn().mockRejectedValue(new Error('You already have a payout request in progress.')) });
    fireEvent.change(input, { target: { value: '200' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request payout' }));
    expect(await screen.findByText(/already have a payout request in progress/i)).toBeInTheDocument();
  });
});
