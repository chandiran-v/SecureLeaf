import { useEffect, useState, type FormEvent } from 'react';
import type { PayoutDetails } from '../../types';

interface Props {
  details: PayoutDetails | null;
  saving: boolean;
  onSave: (details: PayoutDetails) => Promise<void>;
}

/** Phase 09C D2 — where to send the money. A request snapshots these, so editing never redirects one already made. */
export default function PayoutDetailsForm({ details, saving, onSave }: Props) {
  const [upi, setUpi] = useState('');
  const [email, setEmail] = useState('');
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    setUpi(details?.payoutUpi ?? '');
    setEmail(details?.payoutEmail ?? '');
  }, [details?.payoutUpi, details?.payoutEmail]);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setMessage(null);
    try {
      await onSave({ payoutUpi: upi.trim() || null, payoutEmail: email.trim() || null });
      setMessage({ ok: true, text: 'Saved.' });
    } catch (err) {
      setMessage({ ok: false, text: err instanceof Error ? err.message : 'Could not save.' });
    }
  };

  const inputClass = 'mt-1 w-full rounded-lg border border-gray-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500';
  return (
    <form onSubmit={handleSubmit} className="rounded-xl border border-gray-200 bg-white p-5" aria-label="Payout details">
      <h3 className="text-base font-semibold text-gray-900">Payout details</h3>
      <p className="mt-1 text-sm text-gray-500">Where should we send your money? A UPI id is preferred.</p>
      <label htmlFor="payout-upi" className="mt-4 block text-sm font-medium text-gray-700">UPI id</label>
      <input id="payout-upi" value={upi} onChange={(e) => setUpi(e.target.value)} placeholder="name@bank" className={inputClass} />
      <label htmlFor="payout-email" className="mt-3 block text-sm font-medium text-gray-700">Payout email (optional)</label>
      <input id="payout-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" className={inputClass} />
      <div className="mt-3 flex items-center gap-3">
        <button
          type="submit"
          disabled={saving}
          className="rounded-lg border border-gray-200 px-4 py-2 text-sm font-semibold text-gray-700 hover:bg-gray-50 disabled:opacity-50"
        >
          {saving ? 'Saving…' : 'Save details'}
        </button>
        {message && (
          <span role="status" className={`text-xs ${message.ok ? 'text-emerald-700' : 'text-red-600'}`}>{message.text}</span>
        )}
      </div>
    </form>
  );
}
