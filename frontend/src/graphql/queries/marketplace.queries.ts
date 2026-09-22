import { gql } from '@apollo/client';
import { PRODUCT_FIELDS, PRODUCT_CARD_FIELDS } from '../fragments/product.fragments';

export const PRODUCTS = gql`
  ${PRODUCT_CARD_FIELDS}
  query Products($filter: ProductFilterInput, $page: Int, $size: Int) {
    products(filter: $filter, page: $page, size: $size) {
      content {
        ...ProductCardFields
      }
      totalElements
      totalPages
      pageNumber
    }
  }
`;

export const PRODUCT_DETAIL = gql`
  ${PRODUCT_FIELDS}
  query ProductDetail($id: ID!) {
    product(id: $id) {
      ...ProductFields
    }
  }
`;

export const CATEGORIES = gql`
  query Categories {
    categories {
      id
      name
      slug
    }
  }
`;
