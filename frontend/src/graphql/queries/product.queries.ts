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
