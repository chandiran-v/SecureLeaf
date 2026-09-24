import { gql } from '@apollo/client';
import { PRODUCT_FIELDS } from '../fragments/product.fragments';

export const MY_PRODUCTS = gql`
  ${PRODUCT_FIELDS}
  query MyProducts {
    myProducts {
      ...ProductFields
    }
  }
`;
