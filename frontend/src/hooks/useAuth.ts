import { useMutation, useQuery } from '@apollo/client';
import { useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  LOGIN_MUTATION,
  REGISTER_MUTATION,
  GOOGLE_LOGIN_MUTATION,
  LOGOUT_MUTATION,
} from '../graphql/mutations/auth.mutations';
import { ME_QUERY } from '../graphql/queries/auth.queries';
import { useAuthStore } from '../store/authStore';
import type { AuthPayload, User } from '../types';

/**
 * Custom hook encapsulating all authentication operations.
 * Bridges Apollo Client mutations with the Zustand auth store.
 */
export function useAuth() {
  const { setAuth, clearAuth, isAuthenticated } = useAuthStore();
  const navigate = useNavigate();

  // ── Mutations ────────────────────────────────────────────────────────────

  const [loginMutation, { loading: loginLoading, error: loginError }] =
    useMutation<{ login: AuthPayload }>(LOGIN_MUTATION);

  const [registerMutation, { loading: registerLoading, error: registerError }] =
    useMutation<{ register: User }>(REGISTER_MUTATION);

  const [googleLoginMutation, { loading: googleLoading, error: googleError }] =
    useMutation<{ googleLogin: AuthPayload }>(GOOGLE_LOGIN_MUTATION);

  const [logoutMutation] = useMutation(LOGOUT_MUTATION);

  // ── Session hydration (check if user is already logged in) ────────────

  const { data: meData, loading: meLoading } = useQuery<{ me: User }>(ME_QUERY, {
    skip: !isAuthenticated,
    errorPolicy: 'ignore',
  });

  useEffect(() => {
    if (meData?.me && isAuthenticated) {
      const store = useAuthStore.getState();
      if (store.accessToken && store.refreshToken) {
        setAuth(meData.me, store.accessToken, store.refreshToken);
      }
    }
  }, [meData, isAuthenticated, setAuth]);

  // ── Login ─────────────────────────────────────────────────────────────────

  const login = useCallback(
    async (email: string, password: string) => {
      const { data } = await loginMutation({
        variables: { input: { email, password } },
      });
      if (data?.login) {
        setAuth(data.login.user, data.login.accessToken, data.login.refreshToken);
        navigate('/');
      }
    },
    [loginMutation, setAuth, navigate]
  );

  // ── Register ──────────────────────────────────────────────────────────────

  const register = useCallback(
    async (displayName: string, email: string, password: string) => {
      await registerMutation({
        variables: { input: { email, password, displayName } },
      });
      // Auto-login after registration
      await login(email, password);
    },
    [registerMutation, login]
  );

  // ── Google Login ──────────────────────────────────────────────────────────

  const googleLogin = useCallback(
    async (idToken: string) => {
      const { data } = await googleLoginMutation({
        variables: { idToken },
      });
      if (data?.googleLogin) {
        setAuth(
          data.googleLogin.user,
          data.googleLogin.accessToken,
          data.googleLogin.refreshToken
        );
        navigate('/');
      }
    },
    [googleLoginMutation, setAuth, navigate]
  );

  // ── Logout ────────────────────────────────────────────────────────────────

  const logout = useCallback(async () => {
    try {
      await logoutMutation();
    } catch {
      // Even if the server call fails, clear local state
    }
    clearAuth();
    navigate('/login');
  }, [logoutMutation, clearAuth, navigate]);

  return {
    login,
    register,
    googleLogin,
    logout,
    isAuthenticated,
    isLoading: loginLoading || registerLoading || googleLoading || meLoading,
    error: loginError || registerError || googleError,
  };
}
