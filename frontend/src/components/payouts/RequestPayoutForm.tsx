import { useState, type FormEvent } from 'react';
import { formatRupees } from '../../lib/formatPrice';
import { MIN_PAYOUT_PAISE, parseRupeesToPaise, validatePayoutAmount } from '../../lib/payoutAmount';
import type { CreatorBalance } from '../../types';

interface Props {
  balance: CreatorBalance | null;
  /** A REQUESTED/APPROVED payout already exists — the server allows only one at a time (D2). */
  hasOpenRequest: boolean;
  hasPayoutDetails: boolean;
  submitting: boolean;
  onSubmit: (amountPaise: number) => Promise<void>;
}

/** Phase 09C D2 — request-payout form. Validates in the browser for fast feedback; the server re-checks everything. */
export default function RequestPayoutForm({ balance, hasOpenRequest, hasPayoutDetails, submitting, onSubmit }: Props) {
  const [amount, setAmount] = useState('');
  const [touched, setTouched] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const available = balance?.availablePaise ?? 0;
  const validationError = validatePayoutAmount(amount, available);
  const blockedReason = !hasPayoutDetails
    ? 'Add your UPI id or email below before requesting a payout.'
    : hasOpenRequest
      ? 'You already have a payout in progress. You can request another once it is paid or rejected.'
      : available < MIN_PAYOUT_PAISE
        ? `You need at least ${formatRupees(MIN_PAYOUT_PAISE)} available to request a payout.`
        : null;

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    setServerError(null);
    const paise = parseRupeesToPaise(amount);
    if (validationError || paise === null) return;
    try {
      await onSubmit(paise);
      setAmount('');
      setTouched(false);
    } catch (err) {
      setServerError(err instanceof Error ? err.message : 'Could not request the payout.');
    }
  };

  return (
    <form onSubmit={handleSubmit} className="rounded-xl border border-gray-200 bg-white p-5" aria-label="Request a payout" noValidate>
      <h3 className="text-base font-semibold text-gray-900">Request a payout</h3>
      <p className="mt-1 text-sm text-gray-500">
        Minimum {formatRupees(MIN_PAYOUT_PAISE)}. We send it manually to your UPI id or email after approval.
      </p>

      <label htmlFor="payout-amount" className="mt-4 block text-sm font-medium text-gray-700">Amount (₹)</label>
      <input
        id="payout-amount"
        inputMode="decimal"
        value={amount}
        onChange={(e) => setAmount(e.target.value)}
        onBlur={() => setTouched(true)}
        disabled={blockedReason !== null || submitting}
        placeholder={`Up to ${formatRupees(available)}`}
        aria-invalid={touched && validationError !== null}
        aria-describedby="payout-amount-error"
        className="mt-1 w-full sm:w-64 rounded-lg border border-gray-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500 disabled:bg-gray-50"
      />
      <p id="payout-amount-error" role="alert" className="mt-1 min-h-[1.25rem] text-xs text-red-600">
        {touched && amount !== '' ? validationError : null}
        {serverError}
      </p>

      {blockedReason && <p className="mt-1 text-xs text-amber-700">{blockedReason}</p>}

      <button
        type="submit"
        disabled={blockedReason !== null || submitting}
        className="mt-3 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {submitting ? 'Requesting…' : 'Request payout'}
      </button>
    </form>
  );
}
