import { useLocation, useNavigate } from 'react-router-dom';
import Modal from '../ui/Modal';
import { useSessionStore, type SessionEndReason } from '../../store/sessionStore';

const COPY: Record<SessionEndReason, { title: string; body: string }> = {
  expired: {
    title: 'Your session has expired',
    body: "For your security, you've been signed out after a period of inactivity. Log in again to pick up where you left off.",
  },
  invalid: {
    title: 'Your session is no longer valid',
    body: 'You were signed out. This happens if you logged out on another device, your password was reset, or your access changed. Please log in again.',
  },
};

/**
 * Mounted once at the app root. Opens whenever lib/session.ts ends a session. The user must
 * choose: log in again (coming back to this page afterwards) or carry on as a guest.
 */
export default function SessionExpiredModal() {
  const reason = useSessionStore((state) => state.endedReason);
  const dismiss = useSessionStore((state) => state.dismiss);
  const navigate = useNavigate();
  const location = useLocation();

  if (!reason) return null;
  const copy = COPY[reason];

  const logInAgain = () => {
    dismiss();
    navigate('/login', { state: { from: location } });
  };

  const continueAsGuest = () => {
    dismiss();
    navigate('/marketplace');
  };

  return (
    <Modal
      open
      title={copy.title}
      icon={
        <svg className="h-6 w-6 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
      }
      actions={
        <>
          <button
            onClick={continueAsGuest}
            className="rounded-lg px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-100"
          >
            Continue browsing
          </button>
          <button
            onClick={logInAgain}
            className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-emerald-700"
          >
            Log in again
          </button>
        </>
      }
    >
      {copy.body}
    </Modal>
  );
}
