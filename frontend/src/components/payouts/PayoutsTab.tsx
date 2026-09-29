import { useCreatorPayouts } from '../../hooks/useCreatorPayouts';
import { formatRupees } from '../../lib/formatPrice';
import BalanceCard from './BalanceCard';
import RequestPayoutForm from './RequestPayoutForm';
import PayoutDetailsForm from './PayoutDetailsForm';
import PayoutStatusBadge from './PayoutStatusBadge';

/** Phase 09C — the creator dashboard's "Payouts" tab: balance, request form, details and history. */
export default function PayoutsTab() {
  const { balance, payouts, details, requestPayout, requesting, savePayoutDetails, savingDetails } = useCreatorPayouts();
  const hasOpenRequest = payouts.some((p) => p.status === 'REQUESTED' || p.status === 'APPROVED' || p.status === 'PROCESSING');
  const hasPayoutDetails = !!(details?.payoutUpi || details?.payoutEmail);

  return (
    <div className="space-y-6">
      <BalanceCard balance={balance} />

      <div className="grid gap-6 lg:grid-cols-2">
        <RequestPayoutForm
          balance={balance}
          hasOpenRequest={hasOpenRequest}
          hasPayoutDetails={hasPayoutDetails}
          submitting={requesting}
          onSubmit={requestPayout}
        />
        <PayoutDetailsForm details={details} saving={savingDetails} onSave={savePayoutDetails} />
      </div>

      <section aria-label="Payout history">
        <h3 className="mb-3 text-base font-semibold text-gray-900">Payout history</h3>
        {payouts.length === 0 ? (
          <p className="rounded-xl border border-dashed border-gray-200 py-10 text-center text-sm text-gray-500">
            No payouts yet.
          </p>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-gray-200 bg-white">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-gray-100 bg-gray-50/60 text-xs uppercase tracking-wide text-gray-500">
                  {['Requested', 'Amount', 'Status', 'Sent to', 'Reference / note'].map((h) => (
                    <th key={h} className="px-5 py-3 font-semibold">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {payouts.map((p) => (
                  <tr key={p.id}>
                    <td className="px-5 py-3 text-gray-500">{new Date(p.requestedAt).toLocaleDateString()}</td>
                    <td className="px-5 py-3 font-medium text-gray-900">{formatRupees(p.amountPaise)}</td>
                    <td className="px-5 py-3"><PayoutStatusBadge status={p.status} /></td>
                    <td className="px-5 py-3 text-gray-600">{p.payoutDestination ?? '—'}</td>
                    <td className="px-5 py-3 text-gray-500">{p.payoutReference ?? p.notes ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
