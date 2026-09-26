import { useState } from 'react';
import { Link } from 'react-router-dom';
import AuthLayout from '../../components/layout/AuthLayout';
import { usePasswordReset } from '../../hooks/usePasswordReset';

/**
 * AUTH-07 (D8) — always shows the same "check your email" message, whether the address
 * has an account, uses Google, or doesn't exist at all. That's what makes the backend's
 * enumeration-safe `requestPasswordReset` (always returns true) actually enumeration-safe
 * end to end: a UI that only showed this message on success would leak the same
 * "does this account exist" signal the API was designed to hide.
 */
export default function ForgotPasswordPage() {
  const { requestReset, requestLoading } = usePasswordReset();
  const [email, setEmail] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await requestReset(email);
    } catch {
      // Deliberately swallowed — see the class doc above.
    }
    setSubmitted(true);
  };

  if (submitted) {
    return (
      <AuthLayout title="Check your email" subtitle="">
        <p className="text-sm text-gray-600 leading-relaxed">
          If an account exists for <span className="font-medium text-gray-900">{email}</span>, we've sent a link to
          reset your password. The link expires in 30 minutes and can only be used once.
        </p>
        <Link to="/login" className="mt-6 inline-block text-sm font-medium text-emerald-600 hover:text-emerald-700">
          Back to login
        </Link>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title="Forgot your password?" subtitle="Enter your email and we'll send you a reset link">
      <form onSubmit={handleSubmit} className="space-y-5">
        <div>
          <label htmlFor="forgot-email" className="block text-sm font-medium text-gray-700 mb-1.5">
            Email address
          </label>
          <input
            id="forgot-email"
            type="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            autoComplete="email"
            className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-gray-900 placeholder-gray-400 text-sm
                       transition-colors focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
          />
        </div>

        <button
          type="submit"
          disabled={requestLoading}
          className="w-full rounded-lg bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm
                     transition-all hover:bg-emerald-700 focus:outline-none focus:ring-2 focus:ring-emerald-500/50
                     disabled:opacity-50 disabled:cursor-not-allowed active:scale-[0.98]"
        >
          {requestLoading ? 'Sending…' : 'Send reset link'}
        </button>

        <p className="text-center text-sm text-gray-500 mt-6">
          <Link to="/login" className="font-medium text-emerald-600 hover:text-emerald-700 transition-colors">
            Back to login
          </Link>
        </p>
      </form>
    </AuthLayout>
  );
}
