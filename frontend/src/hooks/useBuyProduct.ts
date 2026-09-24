import { useCallback, useState } from 'react';
import { useMutation } from '@apollo/client';
import { useNavigate } from 'react-router-dom';
import { INITIATE_ORDER } from '../graphql/mutations/commerce.mutations';
import type { InitiateOrderPayload } from '../types';

function newIdempotencyKey(): string {
  // crypto.randomUUID is available in every modern browser (secure contexts, incl. localhost).
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * The Buy button's logic.
 *
 * IDEMPOTENCY KEY LIFETIME
 * One key per visit to the product page (created once, in useState's initializer). Every
 * click during this visit — including an accidental double-click, or a retry after a
 * network error — sends the SAME key, so the server returns the same order instead of
 * creating a second one (D2 layer 1). Generating the key inside `buy()` would defeat the
 * whole point: each click would look like a brand-new purchase.
 */
export function useBuyProduct(productId: string) {
  const navigate = useNavigate();
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [initiateOrder, { loading, error }] = useMutation<{ initiateOrder: InitiateOrderPayload }>(INITIATE_ORDER);

  const buy = useCallback(async () => {
    const { data } = await initiateOrder({ variables: { productId, idempotencyKey } });
    const payload = data?.initiateOrder;
    if (!payload) return;

    if (payload.order.status === 'COMPLETED') {
      navigate('/library');                        // free product — already yours (D10)
    } else {
      navigate(`/checkout/${payload.order.id}`);
    }
  }, [initiateOrder, productId, idempotencyKey, navigate]);

  return { buy, loading, error };
}
