import { formatRupees } from '../../lib/formatPrice';
import type { CreatorBalance } from '../../types';

/** Phase 09C D1 — the four balance figures. "Available" is the one a payout can be requested from. */
export default function BalanceCard({ balance }: { balance: CreatorBalance | null }) {
  const show = (paise: number | undefined) => (paise === undefined ? '—' : formatRupees(paise));
  const items = [
    { label: 'Available to withdraw', value: show(balance?.availablePaise), hint: 'Earnings older than 7 days', primary: true },
    { label: 'Pending', value: show(balance?.pendingPaise), hint: 'Still in the 7-day refund window', primary: false },
    { label: 'Paid out', value: show(balance?.paidOutPaise), hint: 'Already sent to you', primary: false },
    { label: 'Lifetime earnings', value: show(balance?.lifetimeEarningsPaise), hint: 'After the 10% commission', primary: false },
  ];
  return (
    <section aria-label="Balance" className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      {items.map((item) => (
        <div
          key={item.label}
          className={`rounded-xl border px-5 py-4 ${
            item.primary ? 'bg-gradient-to-br from-emerald-50 to-teal-50 border-emerald-200' : 'bg-white border-gray-200'
          }`}
        >
          <p className="text-xs text-gray-500 mb-1">{item.label}</p>
          <p className="text-2xl font-bold text-gray-900" data-testid={`balance-${item.label}`}>{item.value}</p>
          <p className="text-[11px] text-gray-400 mt-1">{item.hint}</p>
        </div>
      ))}
    </section>
  );
}
