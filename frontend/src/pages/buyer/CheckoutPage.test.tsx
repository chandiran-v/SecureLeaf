import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MockedProvider, type MockedResponse } from '@apollo/client/testing';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import CheckoutPage from './CheckoutPage';
import { ORDER } from '../../graphql/queries/commerce.queries';
import { VERIFY_PAYMENT } from '../../graphql/mutations/commerce.mutations';
import { payWithMockGateway } from '../../lib/mockGateway';
import { PLATFORM_INFO } from '../../graphql/queries/commerce.queries';
import { useAuthStore } from '../../store/authStore';
import type { RazorpayConstructor, RazorpayFailedResponse, RazorpayOptions } from '../../lib/razorpay';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));
vi.mock('../../lib/mockGateway', () => ({ payWithMockGateway: vi.fn() }));

const pendingOrder = {
  __typename: 'Order',
  id: '42',
  status: 'PENDING',
  totalAmountPaise: 49900,
  gatewayOrderId: 'order_ABC',
  gatewayKeyId: 'rzp_test_key',
  paymentProvider: 'MOCK',
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

// ── Phase 09B — real Razorpay checkout (acceptance criterion 9) ─────────────────

const razorpayOrder = { ...pendingOrder, paymentProvider: 'RAZORPAY' };

const razorpayOrderMock = (status = 'PENDING'): MockedResponse => ({
  request: { query: ORDER, variables: { id: '42' } },
  result: { data: { order: { ...razorpayOrder, status } } },
});

const platformInfoMock = (paymentMode: string): MockedResponse => ({
  request: { query: PLATFORM_INFO },
  result: { data: { platformInfo: { __typename: 'PlatformInfo', paymentMode, supportEmail: 'help@example.com' } } },
});

describe('CheckoutPage with Razorpay', () => {
  let constructed: RazorpayOptions[];
  let openSpy: ReturnType<typeof vi.fn>;
  let listeners: Record<string, (r: RazorpayFailedResponse) => void>;

  beforeEach(() => {
    constructed = [];
    listeners = {};
    openSpy = vi.fn();
    // A stand-in for checkout.js: records the options it was built with, and lets tests fire the callbacks.
    window.Razorpay = class {
      constructor(options: RazorpayOptions) {
        constructed.push(options);
      }
      on(event: 'payment.failed', callback: (r: RazorpayFailedResponse) => void) {
        listeners[event] = callback;
      }
      open = openSpy;
    } as unknown as RazorpayConstructor;
    useAuthStore.setState({
      user: { id: '9', email: 'bala@example.com', displayName: 'Bala Buyer', roles: ['BUYER'], createdAt: '' },
    });
  });

  it('opens the popup with the right options (key, order, amount, prefill, theme)', async () => {
    renderCheckout([razorpayOrderMock(), platformInfoMock('TEST')]);

    fireEvent.click(await screen.findByRole('button', { name: /pay ₹499/i }));

    await waitFor(() => expect(openSpy).toHaveBeenCalledTimes(1));
    expect(constructed).toHaveLength(1);
    expect(constructed[0]).toMatchObject({
      key: 'rzp_test_key',
      order_id: 'order_ABC',
      amount: 49900,
      currency: 'INR',
      name: 'SecureLeaf',
      description: 'Paid Guide',
      prefill: { email: 'bala@example.com', name: 'Bala Buyer' },
    });
    expect(constructed[0].theme?.color).toMatch(/^#[0-9a-f]{6}$/i);
    // The mock gateway is never touched on this path.
    expect(payWithMockGateway).not.toHaveBeenCalled();
  });

  it('sends the popup\'s signed response to verifyPayment and confirms', async () => {
    const verifyMock: MockedResponse = {
      request: {
        query: VERIFY_PAYMENT,
        variables: {
          input: { orderId: '42', gatewayOrderId: 'order_ABC', gatewayPaymentId: 'pay_RZ1', gatewaySignature: 'sig_RZ1' },
        },
      },
      result: { data: { verifyPayment: { ...razorpayOrder, status: 'COMPLETED' } } },
    };
    renderCheckout([razorpayOrderMock(), platformInfoMock('TEST'), verifyMock]);

    fireEvent.click(await screen.findByRole('button', { name: /pay ₹499/i }));
    await waitFor(() => expect(constructed).toHaveLength(1));
    constructed[0].handler({ razorpay_order_id: 'order_ABC', razorpay_payment_id: 'pay_RZ1', razorpay_signature: 'sig_RZ1' });

    expect(await screen.findByText(/payment confirmed/i)).toBeInTheDocument();
  });

  it('shows "Payment cancelled" when the popup is dismissed, and keeps the order payable', async () => {
    renderCheckout([razorpayOrderMock(), platformInfoMock('TEST')]);

    fireEvent.click(await screen.findByRole('button', { name: /pay ₹499/i }));
    await waitFor(() => expect(constructed).toHaveLength(1));
    constructed[0].modal?.ondismiss?.();

    expect(await screen.findByText(/payment cancelled/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /pay ₹499/i })).toBeEnabled();
  });

  it('shows Razorpay\'s error description when a payment fails', async () => {
    renderCheckout([razorpayOrderMock(), platformInfoMock('TEST')]);

    fireEvent.click(await screen.findByRole('button', { name: /pay ₹499/i }));
    await waitFor(() => expect(listeners['payment.failed']).toBeDefined());
    listeners['payment.failed']({ error: { description: 'Your card has insufficient funds.' } });

    expect(await screen.findByRole('alert')).toHaveTextContent(/insufficient funds/i);
  });

  it('shows the test-mode hints and the (undismissable) demo banner for TEST', async () => {
    sessionStorage.setItem('secureleaf-demo-banner-dismissed', '1');
    renderCheckout([razorpayOrderMock(), platformInfoMock('TEST'), platformInfoMock('TEST')]);

    const hints = await screen.findByTestId('test-hints');
    expect(hints).toHaveTextContent('success@razorpay');
    expect(within(hints).getByRole('link', { name: /test cards and upi/i }))
      .toHaveAttribute('href', expect.stringContaining('razorpay.com/docs'));
    expect(await screen.findByTestId('demo-banner')).toBeInTheDocument();
  });

  it('does not show test hints or the banner in LIVE mode', async () => {
    renderCheckout([razorpayOrderMock(), platformInfoMock('LIVE'), platformInfoMock('LIVE')]);

    await screen.findByRole('button', { name: /pay ₹499/i });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId('test-hints')).not.toBeInTheDocument();
    expect(screen.queryByTestId('demo-banner')).not.toBeInTheDocument();
  });

  it('keeps the mock flow for MOCK orders (simulate buttons, no test hints)', async () => {
    renderCheckout([orderMock('PENDING'), platformInfoMock('MOCK')]);

    expect(await screen.findByRole('button', { name: /simulate decline/i })).toBeInTheDocument();
    expect(screen.queryByTestId('test-hints')).not.toBeInTheDocument();
  });
});
