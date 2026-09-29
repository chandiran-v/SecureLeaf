import { useContext, useState } from 'react';
import { getApolloContext } from '@apollo/client';
import { usePlatformInfo } from '../../hooks/usePlatformInfo';

const DEMO_BANNER_DISMISS_KEY = 'secureleaf-demo-banner-dismissed';

function wasDismissed(): boolean {
  try {
    return sessionStorage.getItem(DEMO_BANNER_DISMISS_KEY) === '1';
  } catch {
    return false;   // storage blocked (private mode…) — just keep showing it
  }
}

/**
 * Site-wide "this is a demo" strip (Phase 09B D9).
 *
 * We deploy publicly with Razorpay TEST keys, so anyone can check out with test cards and no real
 * money moves. That has to be unmistakable, or a visitor may worry they were charged (or, worse,
 * type a real card number into a test form). Shown whenever the server's payment mode is not LIVE.
 *
 * Dismissable for the rest of the browser session (sessionStorage, so it comes back next visit),
 * but `alwaysShow` — used by the checkout page — ignores the dismissal: the one place money is
 * discussed must always carry the warning.
 */
export default function DemoBanner({ alwaysShow = false }: { alwaysShow?: boolean }) {
  // A purely informational strip must never take a page down: with no Apollo client in the tree
  // (e.g. a component test that renders AppLayout alone) it simply renders nothing.
  const { client } = useContext(getApolloContext());
  return client ? <DemoBannerContent alwaysShow={alwaysShow} /> : null;
}

function DemoBannerContent({ alwaysShow }: { alwaysShow: boolean }) {
  const { info } = usePlatformInfo();
  const [dismissed, setDismissed] = useState(wasDismissed);

  if (!info || info.paymentMode === 'LIVE') return null;
  if (dismissed && !alwaysShow) return null;

  const dismiss = () => {
    try {
      sessionStorage.setItem(DEMO_BANNER_DISMISS_KEY, '1');
    } catch {
      /* ignore — the banner just hides for this page view */
    }
    setDismissed(true);
  };

  return (
    <div role="status" data-testid="demo-banner"
         className="bg-gradient-to-r from-amber-400 to-orange-400 text-amber-950 text-xs sm:text-sm">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-1.5 flex items-center justify-between gap-3">
        <p className="text-center flex-1">
          <span className="font-semibold">Demo mode</span> —{' '}
          {info.paymentMode === 'TEST'
            ? <>payments use Razorpay <strong>test mode</strong>. No real money is charged.</>
            : <>payments are simulated. No real money is charged.</>}
        </p>
        {!alwaysShow && (
          <button type="button" onClick={dismiss} aria-label="Dismiss demo banner"
                  className="shrink-0 rounded p-1 hover:bg-amber-500/40 transition-colors">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        )}
      </div>
    </div>
  );
}
