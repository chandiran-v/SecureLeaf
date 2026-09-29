import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@apollo/client';
import { ORDER } from '../graphql/queries/commerce.queries';
import { VERIFY_PAYMENT } from '../graphql/mutations/commerce.mutations';
import { payWithMockGateway, type MockOutcome } from '../lib/mockGateway';
import type { RazorpaySuccessResponse } from '../lib/razorpay';
import { useAuthStore } from '../store/authStore';
import { useRazorpayCheckout } from './useRazorpayCheckout';
import type { GatewaySuccessResponse, Order } from '../types';

export type CheckoutPhase =
  | 'idle'          // waiting for the buyer to pay
  | 'paying'        // talking to the gateway
  | 'verifying'     // sending the gateway's signed response to our server
  | 'confirming'    // outcome unknown (timeout) — polling until the webhook settles it
  | 'unconfirmed'   // gave up polling; the webhook may still arrive later
  | 'declined'      // card declined — the same order can be retried
  | 'cancelled'     // buyer closed the Razorpay popup without paying — the order is still open
  | 'error';

const POLL_INTERVAL_MS = 2_000;
const CONFIRM_GIVE_UP_MS = 60_000;

/**
 * Drives the checkout page.
 *
 * THE BROWSER IS AN UNRELIABLE MESSENGER (D13)
 * The happy path is: gateway says success → we call verifyPayment → COMPLETED. But the
 * gateway can time out, the tab can close, the network can drop between "charged" and
 * "told us". So after any ambiguous outcome this hook does NOT decide what happened — it
 * polls the order and lets the server (fed by the signed webhook) be the source of truth.
 */
export function useCheckout(orderId: string) {
  const [phase, setPhase] = useState<CheckoutPhase>('idle');
  const [message, setMessage] = useState<string | null>(null);
  const giveUpTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const { data, loading, error, startPolling, stopPolling, refetch } = useQuery<{ order: Order | null }>(ORDER, {
    variables: { id: orderId },
    fetchPolicy: 'network-only',
  });
  const [verifyPayment] = useMutation<{ verifyPayment: Order }>(VERIFY_PAYMENT);
  const user = useAuthStore((state) => state.user);
  const { open: openRazorpay } = useRazorpayCheckout();

  const order = data?.order ?? null;
  const isCompleted = order?.status === 'COMPLETED';

  const beginConfirming = useCallback(() => {
    setPhase('confirming');
    startPolling(POLL_INTERVAL_MS);
    giveUpTimer.current = setTimeout(() => {
      stopPolling();
      setPhase('unconfirmed');
    }, CONFIRM_GIVE_UP_MS);
  }, [startPolling, stopPolling]);

  // Stop polling the moment the server says COMPLETED — however it got there.
  useEffect(() => {
    if (isCompleted) {
      stopPolling();
      if (giveUpTimer.current) clearTimeout(giveUpTimer.current);
    }
  }, [isCompleted, stopPolling]);

  useEffect(() => () => {
    if (giveUpTimer.current) clearTimeout(giveUpTimer.current);
  }, []);

  /**
   * The gateway (mock or Razorpay) said "paid" — hand its signed response to OUR server, which
   * checks the signature and completes the order. Same call for both providers (D3): only the
   * way the buyer got here differs.
   */
  const submitVerification = useCallback(async (
    orderId: string,
    response: GatewaySuccessResponse | RazorpaySuccessResponse,
  ) => {
    setPhase('verifying');
    try {
      await verifyPayment({
        variables: {
          input: {
            orderId,
            gatewayOrderId: response.razorpay_order_id,
            gatewayPaymentId: response.razorpay_payment_id,
            gatewaySignature: response.razorpay_signature,
          },
        },
      });
      // The mutation returns the Order; Apollo's normalized cache (Order:<id>) updates
      // the ORDER query in place, so isCompleted flips without a refetch.
      setPhase('idle');
    } catch {
      // We were charged but couldn't tell our server. The webhook will — wait for it.
      beginConfirming();
    }
  }, [verifyPayment, beginConfirming]);

  const pay = useCallback(async (outcome: MockOutcome) => {
    if (!order?.gatewayOrderId) return;
    setMessage(null);
    setPhase('paying');

    const result = await payWithMockGateway(order.gatewayOrderId, outcome);

    switch (result.kind) {
      case 'success':
        await submitVerification(order.id, result.response);
        break;
      case 'declined':
        setMessage(result.reason);
        setPhase('declined');
        break;
      case 'timeout':
        beginConfirming();
        break;
      case 'error':
        setMessage(result.message);
        setPhase('error');
        void refetch();   // maybe it was already paid in another tab
        break;
    }
  }, [order, submitVerification, beginConfirming, refetch]);

  /** Phase 09B D3 — open Razorpay's popup for this order. */
  const payWithRazorpay = useCallback(async () => {
    if (!order?.gatewayOrderId || !order.gatewayKeyId) return;
    setMessage(null);
    setPhase('paying');
    try {
      await openRazorpay({
        key: order.gatewayKeyId,
        gatewayOrderId: order.gatewayOrderId,
        amountPaise: order.totalAmountPaise,
        currency: 'INR',
        description: order.product.title,
        prefill: { email: user?.email, name: user?.displayName },
        onSuccess: (response) => { void submitVerification(order.id, response); },
        onDismiss: () => {
          // Closing the popup is not a failure and not a charge: the order stays open.
          setMessage('Payment cancelled');
          setPhase('cancelled');
        },
        onFailed: (description) => {
          setMessage(description);
          setPhase('declined');
        },
      });
      // The popup is open now; the buyer's next move arrives through the callbacks above.
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Could not open the payment window.');
      setPhase('error');
    }
  }, [order, user, openRazorpay, submitVerification]);

  return { order, loading, error, phase, message, isCompleted, pay, payWithRazorpay };
}
