import { gql } from '@apollo/client';

export const SUSPEND_USER = gql`
  mutation SuspendUser($userId: ID!, $reason: String!) {
    suspendUser(userId: $userId, reason: $reason) {
      id
      accountStatus
    }
  }
`;

export const REACTIVATE_USER = gql`
  mutation ReactivateUser($userId: ID!) {
    reactivateUser(userId: $userId) {
      id
      accountStatus
    }
  }
`;

export const TAKE_DOWN_PRODUCT = gql`
  mutation TakeDownProduct($productId: ID!, $reason: String!) {
    takeDownProduct(productId: $productId, reason: $reason) {
      id
      status
      takedownReason
      takenDownAt
    }
  }
`;

export const RESTORE_PRODUCT = gql`
  mutation RestoreProduct($productId: ID!) {
    restoreProduct(productId: $productId) {
      id
      status
      takedownReason
      takenDownAt
    }
  }
`;
