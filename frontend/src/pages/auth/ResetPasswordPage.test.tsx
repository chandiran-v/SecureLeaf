import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MockedProvider, type MockedResponse } from '@apollo/client/testing';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ResetPasswordPage from './ResetPasswordPage';
import { RESET_PASSWORD_MUTATION } from '../../graphql/mutations/auth.mutations';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

beforeEach(() => navigateMock.mockClear());

function renderPage(mocks: MockedResponse[], path = '/reset-password?token=abc123') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <MockedProvider mocks={mocks}>
        <ResetPasswordPage />
      </MockedProvider>
    </MemoryRouter>
  );
}

describe('ResetPasswordPage', () => {
  it('shows an invalid-link message when the URL has no token', () => {
    renderPage([], '/reset-password');
    expect(screen.getByText(/invalid reset link/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/new password/i)).not.toBeInTheDocument();
  });

  it('rejects mismatched passwords without calling the mutation', () => {
    renderPage([]);

    fireEvent.change(screen.getByLabelText(/^new password$/i), { target: { value: 'first-password12' } });
    fireEvent.change(screen.getByLabelText(/confirm new password/i), { target: { value: 'different-password' } });
    fireEvent.click(screen.getByRole('button', { name: /update password/i }));

    expect(screen.getByText(/passwords do not match/i)).toBeInTheDocument();
  });

  it('rejects a password shorter than the backend minimum', () => {
    renderPage([]);

    fireEvent.change(screen.getByLabelText(/^new password$/i), { target: { value: 'short1' } });
    fireEvent.change(screen.getByLabelText(/confirm new password/i), { target: { value: 'short1' } });
    fireEvent.click(screen.getByRole('button', { name: /update password/i }));

    expect(screen.getByText(/at least 10 characters/i)).toBeInTheDocument();
  });

  it('submits matching, long-enough passwords and redirects to login on success', async () => {
    const mock: MockedResponse = {
      request: { query: RESET_PASSWORD_MUTATION, variables: { token: 'abc123', newPassword: 'brand-new-password' } },
      result: { data: { resetPassword: true } },
    };
    renderPage([mock]);

    fireEvent.change(screen.getByLabelText(/^new password$/i), { target: { value: 'brand-new-password' } });
    fireEvent.change(screen.getByLabelText(/confirm new password/i), { target: { value: 'brand-new-password' } });
    fireEvent.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/login', expect.objectContaining({
      state: expect.objectContaining({ toast: expect.stringContaining('sign in') }),
    })));
  });

  it('shows a generic error when the token is invalid or expired', async () => {
    const mock: MockedResponse = {
      request: { query: RESET_PASSWORD_MUTATION, variables: { token: 'abc123', newPassword: 'brand-new-password' } },
      result: {
        errors: [{ message: 'This reset link is invalid or has expired.', extensions: { code: 'INVALID_TOKEN' } } as never],
      },
    };
    renderPage([mock]);

    fireEvent.change(screen.getByLabelText(/^new password$/i), { target: { value: 'brand-new-password' } });
    fireEvent.change(screen.getByLabelText(/confirm new password/i), { target: { value: 'brand-new-password' } });
    fireEvent.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => expect(screen.getByText(/invalid or has expired/i)).toBeInTheDocument());
    expect(navigateMock).not.toHaveBeenCalled();
  });
});
