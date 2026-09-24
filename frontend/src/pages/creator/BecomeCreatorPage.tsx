import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import AuthLayout from '../../components/layout/AuthLayout';
import { useCreatorProducts } from '../../hooks/useCreatorProducts';

/**
 * BecomeCreatorPage — lets a BUYER opt in to become a CREATOR.
 *
 * On submit:
 * 1. Calls becomeCreator GraphQL mutation → DB grants CREATOR role + upserts CreatorProfile
 * 2. Hook immediately calls refreshToken → new JWT carries ROLE_CREATOR
 * 3. Zustand store is updated with new user + tokens
 * 4. Navigate to /creator — the user now has Creator Dashboard access
 *
 * All fields are optional. The user can fill in bio/payout later from their
 * creator settings. The only mandatory step is clicking "Become a Creator"
 * to trigger the DB role grant.
 */
export default function BecomeCreatorPage() {
  const navigate = useNavigate();
  const { becomeCreator } = useCreatorProducts();

  const [bio, setBio] = useState('');
  const [payoutEmail, setPayoutEmail] = useState('');
  const [payoutUpi, setPayoutUpi] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError(null);
    try {
      await becomeCreator({
        bio: bio.trim() || undefined,
        payoutEmail: payoutEmail.trim() || undefined,
        payoutUpi: payoutUpi.trim() || undefined,
      });
      navigate('/creator');
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Something went wrong';
      setError(msg);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <AuthLayout
      title="Become a Creator"
      subtitle="Start selling your digital content on SecureLeaf"
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        {error && (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
            {error}
          </div>
        )}

        {/* Info card */}
        <div className="rounded-lg bg-emerald-50 border border-emerald-100 px-4 py-3 text-sm text-emerald-800">
          <p className="font-medium mb-1">What you'll be able to do:</p>
          <ul className="list-disc list-inside space-y-1 text-emerald-700">
            <li>Upload PDF documents as protected products</li>
            <li>Set your own pricing (or make it free)</li>
            <li>Track sales and earnings in your dashboard</li>
          </ul>
        </div>

        {/* Bio */}
        <div>
          <label htmlFor="become-creator-bio" className="block text-sm font-medium text-gray-700 mb-1.5">
            Bio <span className="text-gray-400 font-normal">(optional)</span>
          </label>
          <textarea
            id="become-creator-bio"
            rows={3}
            value={bio}
            onChange={(e) => setBio(e.target.value)}
            placeholder="Tell buyers a bit about yourself…"
            className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-gray-900 placeholder-gray-400 text-sm
                       transition-colors focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20 resize-none"
          />
        </div>

        {/* Payout email */}
        <div>
          <label htmlFor="become-creator-payout-email" className="block text-sm font-medium text-gray-700 mb-1.5">
            Payout Email <span className="text-gray-400 font-normal">(optional — for receiving payments)</span>
          </label>
          <input
            id="become-creator-payout-email"
            type="email"
            value={payoutEmail}
            onChange={(e) => setPayoutEmail(e.target.value)}
            placeholder="payout@example.com"
            className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-gray-900 placeholder-gray-400 text-sm
                       transition-colors focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
          />
        </div>

        {/* UPI ID */}
        <div>
          <label htmlFor="become-creator-payout-upi" className="block text-sm font-medium text-gray-700 mb-1.5">
            UPI ID <span className="text-gray-400 font-normal">(optional)</span>
          </label>
          <input
            id="become-creator-payout-upi"
            type="text"
            value={payoutUpi}
            onChange={(e) => setPayoutUpi(e.target.value)}
            placeholder="name@upi"
            className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-gray-900 placeholder-gray-400 text-sm
                       transition-colors focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
          />
        </div>

        <button
          id="become-creator-submit"
          type="submit"
          disabled={isLoading}
          className="w-full rounded-lg bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm
                     transition-all hover:bg-emerald-700 focus:outline-none focus:ring-2 focus:ring-emerald-500/50
                     disabled:opacity-50 disabled:cursor-not-allowed active:scale-[0.98]"
        >
          {isLoading ? (
            <span className="flex items-center justify-center gap-2">
              <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              Setting up your creator account…
            </span>
          ) : (
            'Become a Creator'
          )}
        </button>
      </form>
    </AuthLayout>
  );
}
