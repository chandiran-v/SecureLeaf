import { useMutation } from '@apollo/client';
import { useCallback } from 'react';
import { REQUEST_PASSWORD_RESET_MUTATION, RESET_PASSWORD_MUTATION } from '../graphql/mutations/auth.mutations';

/** AUTH-07 — the forgot-password and reset-password screens' mutations. */
export function usePasswordReset() {
  const [requestMutation, { loading: requestLoading }] =
    useMutation<{ requestPasswordReset: boolean }>(REQUEST_PASSWORD_RESET_MUTATION);

  const [resetMutation, { loading: resetLoading, error: resetError }] =
    useMutation<{ resetPassword: boolean }>(RESET_PASSWORD_MUTATION);

  const requestReset = useCallback(
    async (email: string) => {
      await requestMutation({ variables: { email } });
    },
    [requestMutation]
  );

  const resetPassword = useCallback(
    async (token: string, newPassword: string) => {
      await resetMutation({ variables: { token, newPassword } });
    },
    [resetMutation]
  );

  return { requestReset, requestLoading, resetPassword, resetLoading, resetError };
}
