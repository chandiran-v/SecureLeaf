import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@apollo/client';
import AppLayout from '../../components/layout/AppLayout';
import { ORDER_RECEIPT } from '../../graphql/queries/payout.queries';
import { formatRupees } from '../../lib/formatPrice';
import type { OrderReceipt } from '../../types';

const MODE_LABEL: Record<OrderReceipt['paymentMode'], string> = {
  MOCK: 'Demo payment (no money moved)',
  TEST: 'Test payment (no money moved)',
  LIVE: 'Live payment',
};

/**
 * /orders/:id/receipt — a printable proof of purchase (Phase 09C D5). Owner-only: the server
 * answers "not found" for anyone else's order. Print styling: `.no-print` hides the site chrome and
 * the Print button, so paper shows just the receipt. This page is printable because the
 * print-blocking CSS is scoped to the reader (`body.sl-reading`), not the whole site.
 */
export default function ReceiptPage() {
  const { id } = useParams<{ id: string }>();
  const { data, loading, error } = useQuery<{ orderReceipt: OrderReceipt }>(ORDER_RECEIPT, {
    variables: { orderId: id },
    skip: !id,
  });
  const receipt = data?.orderReceipt;

  return (
    <AppLayout>
      <div className="max-w-2xl mx-auto px-4 sm:px-6 py-10">
        {loading && !receipt ? (
          <p className="py-20 text-center text-sm text-gray-500">Loading receipt…</p>
        ) : error || !receipt ? (
          <div className="py-20 text-center">
            <h1 className="text-xl font-semibold text-gray-900">Receipt not found</h1>
            <p className="mt-2 text-sm text-gray-500">We couldn't find a paid order with that number on your account.</p>
            <Link to="/library" className="mt-4 inline-block text-sm font-medium text-emerald-700 underline">Go to your library</Link>
          </div>
        ) : (
          <>
            <article className="rounded-xl border border-gray-200 bg-white p-8 shadow-sm print:border-0 print:shadow-none" aria-label="Receipt">
              <header className="flex items-start justify-between border-b border-gray-100 pb-4">
                <div>
                  <p className="text-lg font-semibold text-gray-900">Secure<span className="text-emerald-600">Leaf</span></p>
                  <h1 className="text-2xl font-bold text-gray-900">Receipt</h1>
                </div>
                {receipt.status === 'REFUNDED' && (
                  <span className="rounded-full border border-red-200 bg-red-50 px-3 py-1 text-xs font-semibold text-red-700">REFUNDED</span>
                )}
              </header>

              <dl className="mt-5 grid grid-cols-[10rem_1fr] gap-y-3 text-sm">
                <dt className="text-gray-500">Order number</dt>
                <dd className="font-medium text-gray-900">#{receipt.orderId}</dd>
                <dt className="text-gray-500">Date</dt>
                <dd className="text-gray-900">{new Date(receipt.purchasedAt).toLocaleString()}</dd>
                <dt className="text-gray-500">Product</dt>
                <dd className="text-gray-900">{receipt.productTitle}</dd>
                <dt className="text-gray-500">Creator</dt>
                <dd className="text-gray-900">{receipt.creatorName}</dd>
                <dt className="text-gray-500">Amount paid</dt>
                <dd className="text-lg font-bold text-gray-900">{formatRupees(receipt.amountPaise)}</dd>
                <dt className="text-gray-500">Payment id</dt>
                <dd className="font-mono text-gray-900">{receipt.paymentIdMasked ?? '—'}</dd>
                <dt className="text-gray-500">Payment mode</dt>
                <dd className="text-gray-900">{MODE_LABEL[receipt.paymentMode]}</dd>
              </dl>

              <p className="mt-6 border-t border-gray-100 pt-4 text-xs text-gray-400">
                Digital document licence, delivered instantly. Refunds: see our Refund Policy.
              </p>
            </article>

            <div className="no-print mt-6 flex justify-end gap-3">
              <Link to="/library" className="rounded-lg border border-gray-200 px-4 py-2 text-sm font-medium text-gray-600 hover:bg-gray-50">
                Back to library
              </Link>
              <button
                type="button"
                onClick={() => window.print()}
                className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700"
              >
                Print receipt
              </button>
            </div>
          </>
        )}
      </div>
    </AppLayout>
  );
}
