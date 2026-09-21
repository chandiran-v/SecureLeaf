import { gql } from '@apollo/client';
import { USER_FIELDS } from './user.fragments';

export const PRODUCT_FIELDS = gql`
  ${USER_FIELDS}
  fragment ProductFields on Product {
    id
    title
    description
    pricePaise
    status
    creator {
      ...UserFields
    }
    category {
      id
      name
      slug
    }
    tags
    thumbnailUrl
    averageRating
    totalSales
    freePreviewPages
    createdAt
  }
`;
