import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MockedProvider, type MockedResponse } from '@apollo/client/testing';
import { GraphQLError } from 'graphql';
import { describe, it, expect, vi } from 'vitest';
import ReceiptPage from './ReceiptPage';
import { ORDER_RECEIPT } from '../../graphql/queries/payout.queries';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));

function renderReceipt(mock: MockedResponse) {
  render(
    <MemoryRouter initialEntries={['/orders/42/receipt']}>
      <MockedProvider mocks={[mock]}>
        <Routes>
          <Route path="/orders/:id/receipt" element={<ReceiptPage />} />
        </Routes>
      </MockedProvider>
    </MemoryRouter>
  );
}

describe('ReceiptPage', () => {
  it('shows the receipt with a masked payment id and a Print button that is not printed itself', async () => {
    renderReceipt({
      request: { query: ORDER_RECEIPT, variables: { orderId: '42' } },
      result: {
        data: {
          orderReceipt: {
            __typename: 'OrderReceipt', orderId: '42', status: 'COMPLETED', purchasedAt: '2026-09-20T10:00:00Z',
            productTitle: 'Paid Guide', creatorName: 'Casey Creator', amountPaise: 49_900,
            paymentIdMasked: 'pay_••••9876', paymentMode: 'TEST',
          },
        },
      },
    });

    expect(await screen.findByText('Paid Guide')).toBeInTheDocument();
    expect(screen.getByText('#42')).toBeInTheDocument();
    expect(screen.getByText('Casey Creator')).toBeInTheDocument();
    expect(screen.getByText('₹499')).toBeInTheDocument();
    expect(screen.getByText('pay_••••9876')).toBeInTheDocument();
    expect(screen.getByText(/test payment/i)).toBeInTheDocument();
    const print = screen.getByRole('button', { name: 'Print receipt' });
    expect(print.parentElement).toHaveClass('no-print');
  });

  it("says 'not found' for someone else's order (the server answers NOT_FOUND)", async () => {
    renderReceipt({
      request: { query: ORDER_RECEIPT, variables: { orderId: '42' } },
      result: { errors: [new GraphQLError('Order not found', { extensions: { code: 'NOT_FOUND' } })] },
    });
    expect(await screen.findByText('Receipt not found')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Print receipt' })).not.toBeInTheDocument();
  });
});
