import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MockedProvider, type MockedResponse } from '@apollo/client/testing';
import { describe, it, expect } from 'vitest';
import ForgotPasswordPage from './ForgotPasswordPage';
import { REQUEST_PASSWORD_RESET_MUTATION } from '../../graphql/mutations/auth.mutations';

function renderPage(mocks: MockedResponse[]) {
  render(
    <MemoryRouter>
      <MockedProvider mocks={mocks}>
        <ForgotPasswordPage />
      </MockedProvider>
    </MemoryRouter>
  );
}

async function submit(email: string) {
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: email } });
  fireEvent.click(screen.getByRole('button', { name: /send reset link/i }));
}

describe('ForgotPasswordPage', () => {
  it('shows the same generic message for an email the mutation reports success for', async () => {
    const mock: MockedResponse = {
      request: { query: REQUEST_PASSWORD_RESET_MUTATION, variables: { email: 'known@example.com' } },
      result: { data: { requestPasswordReset: true } },
    };
    renderPage([mock]);

    await submit('known@example.com');

    await waitFor(() => expect(screen.getByText(/if an account exists for/i)).toBeInTheDocument());
    expect(screen.getByText('known@example.com')).toBeInTheDocument();
  });

  it('shows the exact same generic message even when the request errors', async () => {
    const mock: MockedResponse = {
      request: { query: REQUEST_PASSWORD_RESET_MUTATION, variables: { email: 'unknown@example.com' } },
      error: new Error('network hiccup'),
    };
    renderPage([mock]);

    await submit('unknown@example.com');

    // Enumeration-safety only holds end to end if a failure looks identical to a success —
    // the page must never show a different message here.
    await waitFor(() => expect(screen.getByText(/if an account exists for/i)).toBeInTheDocument());
  });
});
