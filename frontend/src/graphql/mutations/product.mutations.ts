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

export const BECOME_CREATOR = gql`
  ${USER_FIELDS}
  mutation BecomeCreator($input: BecomeCreatorInput!) {
    becomeCreator(input: $input) {
      ...UserFields
    }
  }
`;
