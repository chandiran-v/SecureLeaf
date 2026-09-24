import { gql } from '@apollo/client';

export const ORDER_FIELDS = gql`
  fragment OrderFields on Order {
    id
    status
    totalAmountPaise
    gatewayOrderId
    failureReason
    createdAt
    product {
      id
      title
      thumbnailUrl
      creator {
        id
        displayName
      }
    }
  }
`;
