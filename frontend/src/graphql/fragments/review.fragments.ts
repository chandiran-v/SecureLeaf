import { gql } from '@apollo/client';

// Review.reviewer is a ReviewerSummary (displayName only) — D5's fix for the email leak.
// Do NOT try to select an email field here; the schema itself makes it impossible.
export const REVIEW_FIELDS = gql`
  fragment ReviewFields on Review {
    id
    rating
    reviewText
    createdAt
    updatedAt
    reviewer {
      displayName
    }
  }
`;
