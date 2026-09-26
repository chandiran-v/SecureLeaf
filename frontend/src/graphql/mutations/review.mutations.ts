import { gql } from '@apollo/client';
import { REVIEW_FIELDS } from '../fragments/review.fragments';

export const SUBMIT_REVIEW = gql`
  ${REVIEW_FIELDS}
  mutation SubmitReview($input: SubmitReviewInput!) {
    submitReview(input: $input) {
      ...ReviewFields
    }
  }
`;

export const DELETE_MY_REVIEW = gql`
  mutation DeleteMyReview($productId: ID!) {
    deleteMyReview(productId: $productId)
  }
`;
