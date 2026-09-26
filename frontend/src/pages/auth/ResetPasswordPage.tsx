import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import AuthLayout from '../../components/layout/AuthLayout';
import { usePasswordReset } from '../../hooks/usePasswordReset';

const MIN_PASSWORD_LENGTH = 10; // matches the backend's registration/reset rule

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const navigate = useNavigate();
  const { resetPassword, resetLoading, resetError } = usePasswordReset();

  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [validationError, setValidationError] = useState('');

  const validate = (): boolean => {
    if (password.length < MIN_PASSWORD_LENGTH) {
      setValidationError(`Password must be at least ${MIN_PASSWORD_LENGTH} characters.`);
      return false;
    }
    if (password !== confirmPassword) {
      setValidationError('Passwords do not match.');
      return false;
    }
    setValidationError('');
    return true;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    try {
      await resetPassword(token, password);
      navigate('/login', { state: { toast: 'Password updated — please sign in with your new password.' } });
    } catch {
      // Error is surfaced by the hook (resetError) below
    }
  };

  const errorMessage =
    validationError ||
    (resetError ? resetError.graphQLErrors?.[0]?.message || 'This reset link is invalid or has expired.' : '');

  if (!token) {
    return (
      <AuthLayout title="Invalid reset link" subtitle="">
        <p className="text-sm text-gray-600">
          This password reset link is missing its token. Request a new one from the login page.
        </p>
        <Link to="/forgot-password" className="mt-6 inline-block text-sm font-medium text-emerald-600 hover:text-emerald-700">
          Request a new link
        </Link>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title="Choose a new password" subtitle="Enter and confirm your new password">
      <form onSubmit={handleSubmit} className="space-y-5">
        {errorMessage && (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700" role="alert">
            {errorMessage}
          </div>
        )}

        <div>
          <label htmlFor="reset-password" className="block text-sm font-medium text-gray-700 mb-1.5">
            New password
          </label>
          <input
            id="reset-password"
            type="password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={`Min. ${MIN_PASSWORD_LENGTH} characters`}
            autoComplete="new-password"
            className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-gray-900 placeholder-gray-400 text-sm
                       transition-colors focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
          />
        </div>

        <div>
          <label htmlFor="reset-confirm" className="block text-sm font-medium text-gray-700 mb-1.5">
            Confirm new password
          </label>
          <input
            id="reset-confirm"
            type="password"
            required
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            placeholder="Re-enter your new password"
            autoComplete="new-password"
            className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-gray-900 placeholder-gray-400 text-sm
                       transition-colors focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
          />
        </div>

        <button
          type="submit"
          disabled={resetLoading}
          className="w-full rounded-lg bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm
                     transition-all hover:bg-emerald-700 focus:outline-none focus:ring-2 focus:ring-emerald-500/50
                     disabled:opacity-50 disabled:cursor-not-allowed active:scale-[0.98]"
        >
          {resetLoading ? 'Updating…' : 'Update password'}
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
