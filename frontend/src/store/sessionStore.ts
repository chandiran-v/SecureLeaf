/**
 * Why the user's login ended, if it ended unexpectedly. SessionExpiredModal reads this.
 *
 * Kept apart from authStore on purpose: authStore is persisted to localStorage, while this is
 * one-off UI state that must not survive a reload (nor show up again in another tab later).
 */
import { create } from 'zustand';
import { useAuthStore } from './authStore';

/**
 * - `expired`: the login simply timed out (refresh token past its lifetime).
 * - `invalid`: the server no longer accepts it (logged out elsewhere, password reset, token
 *   revoked or reused, account access changed).
 */
export type SessionEndReason = 'expired' | 'invalid';

interface SessionState {
  endedReason: SessionEndReason | null;
  /** Signs the user out locally and, if they were signed in, asks the modal to explain why. */
  endSession: (reason: SessionEndReason) => void;
  dismiss: () => void;
}

export const useSessionStore = create<SessionState>((set) => ({
  endedReason: null,
  endSession: (reason) => {
    const wasSignedIn = useAuthStore.getState().isAuthenticated;
    useAuthStore.getState().clearAuth();
    // Only someone who *was* signed in needs telling. A stale token lingering after a logout
    // shouldn't pop a "your session expired" dialog at a visitor.
    if (wasSignedIn) set({ endedReason: reason });
  },
  dismiss: () => set({ endedReason: null }),
}));
