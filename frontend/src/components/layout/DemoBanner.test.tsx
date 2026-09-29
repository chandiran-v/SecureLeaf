import { render, screen, fireEvent } from '@testing-library/react';
import { MockedProvider, type MockedResponse } from '@apollo/client/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import DemoBanner from './DemoBanner';
import { PLATFORM_INFO } from '../../graphql/queries/commerce.queries';
import type { PaymentMode } from '../../types';

const infoMock = (paymentMode: PaymentMode): MockedResponse => ({
  request: { query: PLATFORM_INFO },
  result: { data: { platformInfo: { __typename: 'PlatformInfo', paymentMode, supportEmail: 'help@example.com' } } },
});

function renderBanner(mode: PaymentMode, alwaysShow = false) {
  return render(
    <MockedProvider mocks={[infoMock(mode)]}>
      <DemoBanner alwaysShow={alwaysShow} />
    </MockedProvider>
  );
}

describe('DemoBanner (Phase 09B D9, acceptance criterion 9)', () => {
  beforeEach(() => sessionStorage.clear());

  it('shows the Razorpay test-mode message for TEST', async () => {
    renderBanner('TEST');

    const banner = await screen.findByTestId('demo-banner');
    expect(banner).toHaveTextContent(/demo mode/i);
    expect(banner).toHaveTextContent(/razorpay test mode/i);
    expect(banner).toHaveTextContent(/no real money is charged/i);
  });

  it('says payments are simulated for MOCK', async () => {
    renderBanner('MOCK');

    expect(await screen.findByTestId('demo-banner')).toHaveTextContent(/simulated/i);
  });

  it('is NOT shown for LIVE', async () => {
    renderBanner('LIVE');

    // Give the query time to resolve, then confirm nothing rendered.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId('demo-banner')).not.toBeInTheDocument();
  });

  it('can be dismissed for the rest of the browser session', async () => {
    const { unmount } = renderBanner('TEST');
    fireEvent.click(await screen.findByRole('button', { name: /dismiss demo banner/i }));
    expect(screen.queryByTestId('demo-banner')).not.toBeInTheDocument();
    unmount();

    renderBanner('TEST');   // "navigating" to another page: still dismissed
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId('demo-banner')).not.toBeInTheDocument();
  });

  it('is always shown when alwaysShow is set (checkout), even after dismissal, and has no dismiss button', async () => {
    sessionStorage.setItem('secureleaf-demo-banner-dismissed', '1');
    renderBanner('TEST', true);

    expect(await screen.findByTestId('demo-banner')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /dismiss demo banner/i })).not.toBeInTheDocument();
  });

  it('renders nothing (and does not crash) when there is no Apollo client', () => {
    render(<DemoBanner />);
    expect(screen.queryByTestId('demo-banner')).not.toBeInTheDocument();
  });
});
