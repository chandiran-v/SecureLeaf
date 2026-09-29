import { useCallback } from 'react';
import { loadRazorpay, type RazorpayOptions, type RazorpaySuccessResponse } from '../lib/razorpay';

export interface OpenRazorpayArgs {
  key: string;
  gatewayOrderId: string;
  amountPaise: number;
  currency: string;
  description: string;
  prefill?: { email?: string; name?: string };
  /** The payment went through in the popup — hand the signed response to verifyPayment. */
  onSuccess: (response: RazorpaySuccessResponse) => void;
  /** The buyer closed the popup without paying. */
  onDismiss: () => void;
  /** Razorpay reported a failed attempt (declined card, bank error…). The order stays open. */
  onFailed: (description: string) => void;
}

/**
 * Opens Razorpay's checkout popup (Phase 09B D3). Loads checkout.js once (see lib/razorpay),
 * builds the options exactly as Razorpay documents them and wires the three outcomes.
 *
 * `amount` here only DISPLAYS the price in the popup; what is actually charged is the amount
 * stored on the Razorpay order our server created — so tampering with it in the browser
 * changes nothing.
 */
export function useRazorpayCheckout() {
  const open = useCallback(async (args: OpenRazorpayArgs): Promise<void> => {
    const Razorpay = await loadRazorpay();
    const options: RazorpayOptions = {
      key: args.key,
      order_id: args.gatewayOrderId,
      amount: args.amountPaise,
      currency: args.currency,
      name: 'SecureLeaf',
      description: args.description,
      prefill: args.prefill,
      theme: { color: '#059669' },   // emerald-600, matching the app
      handler: args.onSuccess,
      modal: { ondismiss: args.onDismiss },
    };
    const instance = new Razorpay(options);
    instance.on('payment.failed', (response) => {
      args.onFailed(response.error.description ?? 'The payment failed.');
    });
    instance.open();
  }, []);

  return { open };
}
