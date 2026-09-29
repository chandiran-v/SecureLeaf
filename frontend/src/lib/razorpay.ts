/**
 * Razorpay's checkout.js, loaded on demand (Phase 09B D3).
 *
 * WHY LOAD IT LAZILY, ONCE
 * checkout.js is a third-party script (~100 KB) that only the checkout page needs, so it is not
 * bundled or preloaded: the first time a buyer clicks Pay we append one <script> tag and every
 * later call reuses the same promise. Loading it per click would stack duplicate scripts.
 *
 * SECURITY NOTE: the script is served from checkout.razorpay.com, which the Content-Security-Policy
 * explicitly allows (script-src) — as well as api.razorpay.com (connect-src) and the checkout
 * iframe (frame-src). Nothing else on the page is allowed to load foreign scripts.
 */

export const RAZORPAY_SCRIPT_URL = 'https://checkout.razorpay.com/v1/checkout.js';

/** What checkout.js hands its success handler (snake_case is Razorpay's). */
export interface RazorpaySuccessResponse {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

export interface RazorpayFailedResponse {
  error: {
    code?: string;
    description?: string;
    reason?: string;
    metadata?: { order_id?: string; payment_id?: string };
  };
}

export interface RazorpayOptions {
  key: string;
  order_id: string;
  amount: number;
  currency: string;
  name: string;
  description: string;
  prefill?: { email?: string; name?: string };
  theme?: { color: string };
  handler: (response: RazorpaySuccessResponse) => void;
  modal?: { ondismiss?: () => void };
}

export interface RazorpayInstance {
  open: () => void;
  on: (event: 'payment.failed', callback: (response: RazorpayFailedResponse) => void) => void;
}

export type RazorpayConstructor = new (options: RazorpayOptions) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

let scriptPromise: Promise<RazorpayConstructor> | null = null;

/** Resolves with the `Razorpay` constructor, loading checkout.js the first time. */
export function loadRazorpay(): Promise<RazorpayConstructor> {
  if (window.Razorpay) return Promise.resolve(window.Razorpay);
  if (scriptPromise) return scriptPromise;

  scriptPromise = new Promise<RazorpayConstructor>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = RAZORPAY_SCRIPT_URL;
    script.async = true;
    script.onload = () => {
      if (window.Razorpay) resolve(window.Razorpay);
      else reject(new Error('Razorpay checkout loaded but did not initialise.'));
    };
    script.onerror = () => {
      scriptPromise = null;   // let the buyer retry after a network blip
      script.remove();
      reject(new Error('Could not load the Razorpay checkout. Check your connection and try again.'));
    };
    document.head.appendChild(script);
  });
  return scriptPromise;
}

/** Test helper: forget the cached load so each test starts clean. */
export function resetRazorpayLoaderForTests() {
  scriptPromise = null;
}
