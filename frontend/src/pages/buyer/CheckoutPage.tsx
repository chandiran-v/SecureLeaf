import { Link, useParams } from 'react-router-dom';
import AppLayout from '../../components/layout/AppLayout';
import { useCheckout } from '../../hooks/useCheckout';
import { formatPrice } from '../../lib/formatPrice';
import type { MockOutcome } from '../../lib/mockGateway';

function Spinner() {
  return (
    <svg className="animate-spin h-5 w-5 text-emerald-500" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
    </svg>
  );
}

/**
 * /checkout/:orderId — the mock Razorpay checkout.
 *
 * The three buttons map to the three things a real gateway can do to you: succeed, decline,
 * or go silent. The "timeout" button is the one worth clicking while reading the Phase 4
 * note: the money is captured, the browser is told nothing, and the page still ends up
 * COMPLETED a few seconds later — because the webhook, not the browser, is the source of truth.
 */
export default function CheckoutPage() {
  const { orderId = '' } = useParams<{ orderId: string }>();
  const { order, loading, error, phase, message, isCompleted, pay } = useCheckout(orderId);

  if (loading && !order) {
    return (
      <AppLayout>
        <div className="flex justify-center py-24"><Spinner /></div>
      </AppLayout>
    );
  }

  if (error || !order) {
    return (
      <AppLayout>
        <div className="max-w-md mx-auto px-4 py-20 text-center">
          <h1 className="text-xl font-semibold text-gray-900 mb-2">Order not found</h1>
          <Link to="/marketplace" className="text-emerald-600 text-sm underline">Back to marketplace</Link>
        </div>
      </AppLayout>
    );
  }

  const busy = phase === 'paying' || phase === 'verifying' || phase === 'confirming';

  return (
    <AppLayout>
      <div className="max-w-md mx-auto px-4 py-12">
        {/* ── Order summary ── */}
        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
          <div className="px-6 py-5 border-b border-gray-100">
            <p className="text-xs font-medium text-gray-400 uppercase tracking-wide mb-1">Order #{order.id}</p>
            <h1 className="text-lg font-semibold text-gray-900">{order.product.title}</h1>
            <p className="text-sm text-gray-500">by {order.product.creator.displayName}</p>
          </div>
          <div className="px-6 py-4 flex items-center justify-between bg-gray-50/60">
            <span className="text-sm text-gray-600">Total</span>
            <span className="text-xl font-bold text-gray-900">{formatPrice(order.totalAmountPaise)}</span>
          </div>

          <div className="px-6 py-6">
            {isCompleted ? (
              <div className="text-center" role="status">
                <div className="w-12 h-12 rounded-full bg-emerald-100 text-emerald-600 flex items-center justify-center mx-auto mb-3">
                  <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                  </svg>
                </div>
                <h2 className="font-semibold text-gray-900 mb-1">Payment confirmed</h2>
                <p className="text-sm text-gray-500 mb-5">“{order.product.title}” is now in your library.</p>
                <Link
                  to="/library"
                  className="inline-block w-full py-3 rounded-lg bg-emerald-600 text-white font-semibold text-sm hover:bg-emerald-700 transition-colors"
                >
                  Go to my library
                </Link>
              </div>
            ) : order.status !== 'PENDING' ? (
              <div className="text-center" role="alert">
                <p className="text-sm text-gray-600 mb-4">This order can no longer be paid (status: {order.status}).</p>
                <Link to={`/product/${order.product.id}`} className="text-emerald-600 text-sm underline">
                  Back to the product
                </Link>
              </div>
            ) : (
              <>
                {/* Mock gateway panel — stands in for Razorpay's checkout popup. */}
                <div className="rounded-xl border border-dashed border-indigo-200 bg-indigo-50/40 p-4 mb-4">
                  <p className="text-xs font-semibold text-indigo-700 mb-1">Mock Razorpay checkout</p>
                  <p className="text-xs text-indigo-600/80">
                    No real money moves. Pick what the payment gateway should do.
                  </p>
                </div>

                {phase === 'declined' && message && (
                  <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700 mb-4" role="alert">
                    {message} You can try again — your order is still open.
                  </div>
                )}
                {phase === 'error' && message && (
                  <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700 mb-4" role="alert">
                    {message}
                  </div>
                )}
                {phase === 'confirming' && (
                  <div className="flex items-start gap-3 rounded-lg bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-800 mb-4" role="status">
                    <Spinner />
                    <span>
                      We didn't hear back from the payment gateway. If you were charged, we'll confirm it
                      automatically in a few seconds — please don't pay again.
                    </span>
                  </div>
                )}
                {phase === 'unconfirmed' && (
                  <div className="rounded-lg bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-800 mb-4" role="status">
                    Still waiting for confirmation. If your payment went through, the product will appear in
                    your library and you'll get a notification — no need to pay again.
                  </div>
                )}

                <div className="space-y-2">
                  <PayButton outcome="SUCCESS" onPay={pay} disabled={busy} primary>
                    {phase === 'paying' ? 'Processing…' : phase === 'verifying' ? 'Verifying…' : `Pay ${formatPrice(order.totalAmountPaise)}`}
                  </PayButton>
                  <div className="grid grid-cols-2 gap-2">
                    <PayButton outcome="DECLINE" onPay={pay} disabled={busy}>Simulate decline</PayButton>
                    <PayButton outcome="TIMEOUT" onPay={pay} disabled={busy}>Simulate timeout</PayButton>
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </AppLayout>
  );
}

function PayButton({ outcome, onPay, disabled, primary, children }: {
  outcome: MockOutcome;
  onPay: (outcome: MockOutcome) => void;
  disabled: boolean;
  primary?: boolean;
  children: React.ReactNode;
}) {
  const style = primary
    ? 'w-full py-3 bg-gradient-to-r from-emerald-600 to-teal-600 text-white font-semibold shadow-sm hover:from-emerald-700 hover:to-teal-700'
    : 'py-2 border border-gray-200 text-gray-600 text-xs font-medium hover:bg-gray-50';
  return (
    <button
      type="button"
      onClick={() => onPay(outcome)}
      disabled={disabled}
      className={`${style} rounded-lg text-sm transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed`}
    >
      {children}
    </button>
  );
}
