import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@apollo/client';
import { ORDER } from '../graphql/queries/commerce.queries';
import { VERIFY_PAYMENT } from '../graphql/mutations/commerce.mutations';
import { payWithMockGateway, type MockOutcome } from '../lib/mockGateway';
import type { Order } from '../types';

export type CheckoutPhase =
  | 'idle'          // waiting for the buyer to pay
  | 'paying'        // talking to the gateway
  | 'verifying'     // sending the gateway's signed response to our server
  | 'confirming'    // outcome unknown (timeout) — polling until the webhook settles it
  | 'unconfirmed'   // gave up polling; the webhook may still arrive later
  | 'declined'      // card declined — the same order can be retried
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

  const pay = useCallback(async (outcome: MockOutcome) => {
    if (!order?.gatewayOrderId) return;
    setMessage(null);
    setPhase('paying');

    const result = await payWithMockGateway(order.gatewayOrderId, outcome);

    switch (result.kind) {
      case 'success':
        setPhase('verifying');
        try {
          await verifyPayment({
            variables: {
              input: {
                orderId: order.id,
                gatewayOrderId: result.response.razorpay_order_id,
                gatewayPaymentId: result.response.razorpay_payment_id,
                gatewaySignature: result.response.razorpay_signature,
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
  }, [order, verifyPayment, beginConfirming, refetch]);

  return { order, loading, error, phase, message, isCompleted, pay };
}
