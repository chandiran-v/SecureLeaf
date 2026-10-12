import { useLocation, useNavigate } from 'react-router-dom';
import Modal from '../ui/Modal';

interface SignInPromptModalProps {
  open: boolean;
  /** Why we're asking, e.g. "Sign in to buy this book." */
  message: string;
  onClose: () => void;
}

/**
 * Shown when a guest tries something that needs an account (buying, reading past the free
 * preview). Sign in / Create account carry the current page as `state.from`, so useAuth sends
 * the user straight back here afterwards; "Not now" keeps them browsing as a guest.
 */
export default function SignInPromptModal({ open, message, onClose }: SignInPromptModalProps) {
  const navigate = useNavigate();
  const location = useLocation();

  const goTo = (path: '/login' | '/register') => {
    onClose();
    navigate(path, { state: { from: location } });
  };

  return (
    <Modal
      open={open}
      title="Sign in to continue"
      onClose={onClose}
      icon={
        <svg className="h-6 w-6 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
        </svg>
      }
      actions={
        <>
          <button
            onClick={onClose}
            className="rounded-lg px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-100"
          >
            Not now
          </button>
          <button
            onClick={() => goTo('/register')}
            className="rounded-lg border border-emerald-600 px-4 py-2 text-sm font-semibold text-emerald-700 transition-colors hover:bg-emerald-50"
          >
            Create account
          </button>
          <button
            onClick={() => goTo('/login')}
            className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-emerald-700"
          >
            Sign in
          </button>
        </>
      }
    >
      {message}
    </Modal>
  );
}
