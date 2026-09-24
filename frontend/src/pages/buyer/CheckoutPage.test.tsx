import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MockedProvider, type MockedResponse } from '@apollo/client/testing';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import CheckoutPage from './CheckoutPage';
import { ORDER } from '../../graphql/queries/commerce.queries';
import { VERIFY_PAYMENT } from '../../graphql/mutations/commerce.mutations';
import { payWithMockGateway } from '../../lib/mockGateway';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));
vi.mock('../../lib/mockGateway', () => ({ payWithMockGateway: vi.fn() }));

const pendingOrder = {
  __typename: 'Order',
  id: '42',
  status: 'PENDING',
  totalAmountPaise: 49900,
  gatewayOrderId: 'order_ABC',
  failureReason: null,
  createdAt: new Date().toISOString(),
  product: {
    __typename: 'Product',
    id: '7',
    title: 'Paid Guide',
    thumbnailUrl: null,
    creator: { __typename: 'CreatorSummary', id: 'c1', displayName: 'Casey' },
  },
};

const orderMock = (status: string): MockedResponse => ({
  request: { query: ORDER, variables: { id: '42' } },
  result: { data: { order: { ...pendingOrder, status } } },
});

function renderCheckout(mocks: MockedResponse[]) {
  render(
    <MemoryRouter initialEntries={['/checkout/42']}>
      <MockedProvider mocks={mocks}>
        <Routes>
          <Route path="/checkout/:orderId" element={<CheckoutPage />} />
        </Routes>
      </MockedProvider>
    </MemoryRouter>
  );
}

describe('CheckoutPage', () => {
  beforeEach(() => {
    vi.mocked(payWithMockGateway).mockReset();
  });

  it('pays at the gateway, sends the signed response to verifyPayment, and confirms', async () => {
    vi.mocked(payWithMockGateway).mockResolvedValue({
      kind: 'success',
      response: { razorpay_order_id: 'order_ABC', razorpay_payment_id: 'pay_1', razorpay_signature: 'sig' },
    });
    const verifyMock: MockedResponse = {
      request: {
        query: VERIFY_PAYMENT,
        variables: {
          input: { orderId: '42', gatewayOrderId: 'order_ABC', gatewayPaymentId: 'pay_1', gatewaySignature: 'sig' },
        },
      },
      result: { data: { verifyPayment: { ...pendingOrder, status: 'COMPLETED' } } },
    };
    renderCheckout([orderMock('PENDING'), verifyMock]);

    fireEvent.click(await screen.findByRole('button', { name: /pay ₹499/i }));

    expect(await screen.findByText(/payment confirmed/i)).toBeInTheDocument();
    expect(payWithMockGateway).toHaveBeenCalledWith('order_ABC', 'SUCCESS');
  });

  it('keeps the order open after a decline so the buyer can retry', async () => {
    vi.mocked(payWithMockGateway).mockResolvedValue({ kind: 'declined', reason: 'Card declined.' });
    renderCheckout([orderMock('PENDING')]);

    fireEvent.click(await screen.findByRole('button', { name: /simulate decline/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/card declined.*try again/i);
    expect(screen.getByRole('button', { name: /pay ₹499/i })).toBeEnabled();
  });

  it('on a gateway timeout, does not assume failure — waits for server confirmation', async () => {
    vi.mocked(payWithMockGateway).mockResolvedValue({ kind: 'timeout' });
    renderCheckout([orderMock('PENDING'), orderMock('COMPLETED')]);

    fireEvent.click(await screen.findByRole('button', { name: /simulate timeout/i }));

    expect(await screen.findByText(/please don't pay again/i)).toBeInTheDocument();
    // The poll picks up the COMPLETED status the webhook produced.
    await waitFor(() => expect(screen.getByText(/payment confirmed/i)).toBeInTheDocument(), { timeout: 4000 });
  });

  it('shows the confirmation straight away for an order that is already paid', async () => {
    renderCheckout([orderMock('COMPLETED')]);

    expect(await screen.findByText(/payment confirmed/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /pay/i })).not.toBeInTheDocument();
  });
});
