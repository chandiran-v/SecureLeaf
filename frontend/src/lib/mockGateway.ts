/**
 * Client for the MOCK payment gateway — the stand-in for Razorpay's checkout.js.
 *
 * In the real integration this file is replaced by loading
 * https://checkout.razorpay.com/v1/checkout.js and calling
 *   new Razorpay({ key, order_id, amount, handler: (response) => … }).open()
 * The `handler` receives exactly the GatewaySuccessResponse shape returned here, so the
 * code that calls `verifyPayment` afterwards doesn't change at all.
 *
 * WHY A SEPARATE AXIOS INSTANCE
 * restClient attaches our JWT to every request. This call represents a request to a THIRD
 * PARTY (Razorpay) — our access token must never be sent there. A bare instance keeps that
 * boundary explicit, even though in dev the mock happens to live on our own backend.
 */
import axios from 'axios';
import type { GatewaySuccessResponse } from '../types';

export type MockOutcome = 'SUCCESS' | 'DECLINE' | 'TIMEOUT';

export type GatewayResult =
  | { kind: 'success'; response: GatewaySuccessResponse }
  | { kind: 'declined'; reason: string }
  // The gateway didn't answer in time. The money MAY have been taken — never treat this as
  // a failure. Wait for the server (webhook) to tell us the truth.
  | { kind: 'timeout' }
  | { kind: 'error'; message: string };

const gatewayClient = axios.create({ baseURL: '/api/mock-gateway' });

interface GatewayErrorBody {
  error?: { description?: string };
}

export async function payWithMockGateway(gatewayOrderId: string, outcome: MockOutcome): Promise<GatewayResult> {
  try {
    const res = await gatewayClient.post<GatewaySuccessResponse>(`/orders/${gatewayOrderId}/pay`, { outcome });
    return { kind: 'success', response: res.data };
  } catch (err) {
    if (axios.isAxiosError<GatewayErrorBody>(err) && err.response) {
      const description = err.response.data?.error?.description;
      if (err.response.status === 402) {
        return { kind: 'declined', reason: description ?? 'Payment declined.' };
      }
      if (err.response.status === 504) {
        return { kind: 'timeout' };
      }
      return { kind: 'error', message: description ?? 'Payment could not be processed.' };
    }
    // No response at all (network drop) is just as ambiguous as a timeout.
    return { kind: 'timeout' };
  }
}
