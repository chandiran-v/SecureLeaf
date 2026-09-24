import { gql } from '@apollo/client';

// NOTE: Product.creator is a CreatorSummary (id + displayName only), not a full User —
// the marketplace is public, so the schema itself makes it impossible to select email
// here (see D4, phase-3 design doc). Do NOT spread USER_FIELDS on it.
export const PRODUCT_FIELDS = gql`
  fragment ProductFields on Product {
    id
    title
    description
    pricePaise
    status
    creator {
      id
      displayName
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
    pageCount
    createdAt
  }
`;

// Lighter-weight fragment for the marketplace grid — no description, keeps the list
// query payload small. Detail fields are fetched separately by ProductDetailPage.
export const PRODUCT_CARD_FIELDS = gql`
  fragment ProductCardFields on Product {
    id
    title
    pricePaise
    status
    creator {
      id
      displayName
    }
    category {
      id
      name
      slug
    }
    thumbnailUrl
    averageRating
    totalSales
  }
`;
