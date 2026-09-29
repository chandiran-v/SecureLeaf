import { useState } from 'react';
import AppLayout from '../../components/layout/AppLayout';
import AdminNav from '../../components/admin/AdminNav';
import Pagination from '../../components/marketplace/Pagination';
import ReasonPromptDialog from '../../components/admin/ReasonPromptDialog';
import PayoutStatusBadge from '../../components/payouts/PayoutStatusBadge';
import { useAdminPayouts } from '../../hooks/useAdminPayouts';
import { formatRupees } from '../../lib/formatPrice';
import type { Payout, PayoutStatus } from '../../types';

const STATUS_OPTIONS: PayoutStatus[] = ['REQUESTED', 'APPROVED', 'PAID', 'REJECTED'];

type Pending = { kind: 'paid' | 'reject'; payout: Payout } | null;

/**
 * Phase 09C D3 — the manual payout queue. The admin sends the money by UPI/bank OUTSIDE the app,
 * then records it here: Approve (REQUESTED→APPROVED), Mark paid with the transfer reference
 * (APPROVED→PAID), or Reject with a reason (releases the balance). Buttons only appear for the
 * transitions the server allows; the server enforces them regardless.
 */
export default function AdminPayoutsPage() {
  const {
    status, payouts, totalPages, pageNumber, loading, error, busy,
    approvePayout, markPayoutPaid, rejectPayout, setStatus, setPage,
  } = useAdminPayouts();
  const [pending, setPending] = useState<Pending>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const run = async (action: () => Promise<void>) => {
    setActionError(null);
    try {
      await action();
      setPending(null);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'That action failed.');
      setPending(null);
    }
  };

  return (
    <AppLayout>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <div className="mb-2 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Payouts</h1>
            <p className="text-gray-500 text-sm mt-1">Creator payout requests. Transfers are made manually, then recorded here.</p>
          </div>
          <select
            aria-label="Filter by status"
            value={status ?? ''}
            onChange={(e) => setStatus((e.target.value || undefined) as PayoutStatus | undefined)}
            className="rounded-lg border border-gray-200 px-3 py-2 text-sm"
          >
            <option value="">All statuses</option>
            {STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}
          </select>
        </div>
        <AdminNav />

        {actionError && (
          <p role="alert" className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{actionError}</p>
        )}

        {error ? (
          <p className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">Failed to load payouts.</p>
        ) : loading && payouts.length === 0 ? (
          <p className="py-20 text-center text-sm text-gray-500">Loading…</p>
        ) : payouts.length === 0 ? (
          <p className="py-20 text-center text-sm text-gray-500">No payouts here.</p>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-gray-200 bg-white">
            <table className="w-full text-left" aria-label="Payout requests">
              <thead>
                <tr className="border-b border-gray-100 bg-gray-50/60">
                  {['Creator', 'Amount', 'Send to', 'Status', 'Requested', ''].map((h) => (
                    <th key={h} className="px-6 py-3 text-xs font-semibold uppercase tracking-wide text-gray-500">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {payouts.map((p) => (
                  <tr key={p.id}>
                    <td className="px-6 py-4">
                      <p className="text-sm font-medium text-gray-900">{p.creatorName}</p>
                      <p className="text-xs text-gray-400">{p.creatorEmail}</p>
                    </td>
                    <td className="px-6 py-4 text-sm font-medium text-gray-900">{formatRupees(p.amountPaise)}</td>
                    <td className="px-6 py-4 text-sm text-gray-600">
                      {p.payoutDestination ?? '—'}
                      {p.payoutMethod && <span className="ml-1 text-xs text-gray-400">({p.payoutMethod})</span>}
                    </td>
                    <td className="px-6 py-4">
                      <PayoutStatusBadge status={p.status} />
                      {p.payoutReference && <p className="mt-1 text-[11px] text-gray-400">Ref {p.payoutReference}</p>}
                      {p.notes && <p className="mt-1 max-w-[220px] text-[11px] text-red-600">{p.notes}</p>}
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500">{new Date(p.requestedAt).toLocaleDateString()}</td>
                    <td className="px-6 py-4">
                      <div className="flex items-center justify-end gap-2">
                        {p.status === 'REQUESTED' && (
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() => void run(() => approvePayout(p.id))}
                            className="rounded-lg border border-emerald-200 px-3 py-1.5 text-xs text-emerald-700 hover:bg-emerald-50 disabled:opacity-50"
                          >
                            Approve
                          </button>
                        )}
                        {p.status === 'APPROVED' && (
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() => setPending({ kind: 'paid', payout: p })}
                            className="rounded-lg border border-emerald-200 px-3 py-1.5 text-xs text-emerald-700 hover:bg-emerald-50 disabled:opacity-50"
                          >
                            Mark paid
                          </button>
                        )}
                        {(p.status === 'REQUESTED' || p.status === 'APPROVED') && (
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() => setPending({ kind: 'reject', payout: p })}
                            className="rounded-lg border border-red-100 px-3 py-1.5 text-xs text-red-500 hover:bg-red-50 disabled:opacity-50"
                          >
                            Reject
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <Pagination pageNumber={pageNumber} totalPages={totalPages} onPageChange={setPage} />
      </div>

      {pending?.kind === 'paid' && (
        <ReasonPromptDialog
          title="Mark payout as paid"
          message={`Enter the transfer reference (e.g. the UPI transaction id) for the ${formatRupees(pending.payout.amountPaise)} you sent to ${pending.payout.payoutDestination ?? 'the creator'}.`}
          fieldLabel="Transfer reference"
          confirmLabel="Mark paid"
          danger={false}
          submitting={busy}
          onConfirm={(reference) => void run(() => markPayoutPaid(pending.payout.id, reference))}
          onCancel={() => setPending(null)}
        />
      )}
      {pending?.kind === 'reject' && (
        <ReasonPromptDialog
          title="Reject payout"
          message="The amount goes back to the creator's available balance. They will be told why."
          confirmLabel="Reject"
          submitting={busy}
          onConfirm={(reason) => void run(() => rejectPayout(pending.payout.id, reason))}
          onCancel={() => setPending(null)}
        />
      )}
    </AppLayout>
  );
}
