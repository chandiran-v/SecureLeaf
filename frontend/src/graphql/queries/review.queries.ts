import { gql } from '@apollo/client';
import { REVIEW_FIELDS } from '../fragments/review.fragments';

export const PRODUCT_REVIEWS = gql`
  ${REVIEW_FIELDS}
  query ProductReviews($productId: ID!, $page: Int, $size: Int) {
    productReviews(productId: $productId, page: $page, size: $size) {
      content {
        ...ReviewFields
      }
      totalElements
      totalPages
      pageNumber
    }
  }
`;

export const MY_REVIEW = gql`
  ${REVIEW_FIELDS}
  query MyReview($productId: ID!) {
    myReview(productId: $productId) {
      ...ReviewFields
    }
  }
`;

export const RATING_BREAKDOWN = gql`
  query RatingBreakdown($productId: ID!) {
    ratingBreakdown(productId: $productId) {
      rating
      count
    }
  }
`;
