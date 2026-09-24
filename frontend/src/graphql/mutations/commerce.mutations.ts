import { gql } from '@apollo/client';
import { ORDER_FIELDS } from '../fragments/commerce.fragments';

// idempotencyKey: one UUID per purchase attempt (generated once per product-page visit).
// Sending the same key again — double-click, network retry — returns the SAME order.
export const INITIATE_ORDER = gql`
  ${ORDER_FIELDS}
  mutation InitiateOrder($productId: ID!, $idempotencyKey: String!) {
    initiateOrder(productId: $productId, idempotencyKey: $idempotencyKey) {
      order {
        ...OrderFields
      }
      gatewayOrderId
      gatewayKeyId
      currency
    }
  }
`;

export const VERIFY_PAYMENT = gql`
  ${ORDER_FIELDS}
  mutation VerifyPayment($input: VerifyPaymentInput!) {
    verifyPayment(input: $input) {
      ...OrderFields
    }
  }
`;

export const MARK_NOTIFICATION_READ = gql`
  mutation MarkNotificationRead($id: ID!) {
    markNotificationRead(id: $id)
  }
`;
