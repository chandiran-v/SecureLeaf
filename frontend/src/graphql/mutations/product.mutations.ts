import { gql } from '@apollo/client';
import { PRODUCT_FIELDS } from '../fragments/product.fragments';
import { USER_FIELDS } from '../fragments/user.fragments';

export const CREATE_PRODUCT = gql`
  ${PRODUCT_FIELDS}
  mutation CreateProduct($input: CreateProductInput!) {
    createProduct(input: $input) {
      ...ProductFields
    }
  }
`;

export const UNPUBLISH_PRODUCT = gql`
  ${PRODUCT_FIELDS}
  mutation UnpublishProduct($id: ID!) {
    unpublishProduct(id: $id) {
      ...ProductFields
    }
  }
`;

export const DELETE_PRODUCT = gql`
  mutation DeleteProduct($id: ID!) {
    deleteProduct(id: $id)
  }
`;

// Phase 6, D4 — state-transition recovery from the dashboard.
export const RETRY_PROCESSING = gql`
  ${PRODUCT_FIELDS}
  mutation RetryProcessing($productId: ID!) {
    retryProcessing(productId: $productId) {
      ...ProductFields
      salesCount
      netEarningsPaise
      processingStage
      failureReason
    }
  }
`;

export const REPUBLISH_PRODUCT = gql`
  ${PRODUCT_FIELDS}
  mutation RepublishProduct($productId: ID!) {
    republishProduct(productId: $productId) {
      ...ProductFields
      salesCount
      netEarningsPaise
      processingStage
      failureReason
    }
  }
`;

export const BECOME_CREATOR = gql`
  ${USER_FIELDS}
  mutation BecomeCreator($input: BecomeCreatorInput!) {
    becomeCreator(input: $input) {
      ...UserFields
    }
  }
`;
