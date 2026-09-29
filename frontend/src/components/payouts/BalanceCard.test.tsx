import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import BalanceCard from './BalanceCard';

describe('BalanceCard', () => {
  it('shows all four figures from the server', () => {
    render(<BalanceCard balance={{ availablePaise: 115_000, pendingPaise: 90_000, lifetimeEarningsPaise: 225_000, paidOutPaise: 20_000 }} />);
    expect(screen.getByTestId('balance-Available to withdraw')).toHaveTextContent('₹1,150');
    expect(screen.getByTestId('balance-Pending')).toHaveTextContent('₹900');
    expect(screen.getByTestId('balance-Paid out')).toHaveTextContent('₹200');
    expect(screen.getByTestId('balance-Lifetime earnings')).toHaveTextContent('₹2,250');
  });

  it('shows placeholders while the balance has not loaded', () => {
    render(<BalanceCard balance={null} />);
    expect(screen.getByTestId('balance-Available to withdraw')).toHaveTextContent('—');
  });
});
