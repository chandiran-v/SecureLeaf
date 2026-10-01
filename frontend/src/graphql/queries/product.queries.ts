import { gql } from '@apollo/client';
import { PRODUCT_FIELDS } from '../fragments/product.fragments';

// Dashboard-only fields (salesCount/netEarningsPaise/processingStage/failureReason) are kept out
// of the shared PRODUCT_FIELDS fragment on purpose: every other screen that spreads that
// fragment (marketplace grid, product detail) has no use for them, and salesCount/netEarningsPaise
// resolve to null for anyone but the product's own creator anyway (Phase 6, D2).
export const MY_PRODUCTS = gql`
  ${PRODUCT_FIELDS}
  query MyProducts {
    myProducts {
      ...ProductFields
      salesCount
      netEarningsPaise
      processingStage
      failureReason
      takedownReason
    }
  }
`;

// Phase 15, D7 — the creator's version history for one product (owner-only on the server).
export const PRODUCT_VERSIONS = gql`
  query ProductVersions($productId: ID!) {
    productVersions(productId: $productId) {
      id
      versionNumber
      createdAt
      pageCount
      status
      updatePolicy
      buyerCount
      current
      failureReason
      migrationPending
    }
  }
`;
