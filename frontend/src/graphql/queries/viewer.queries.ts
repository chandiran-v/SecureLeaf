import { gql } from '@apollo/client';

// D5 — mints one signed, single-use tile URL, valid ~30s. Re-query this for every page turn;
// never reuse a `url` (the tile endpoint enforces single-use server-side and 403s a replay).
export const VIEWER_PAGE_URL = gql`
  query ViewerPageUrl($sessionToken: String!, $pageNumber: Int!) {
    viewerPageUrl(sessionToken: $sessionToken, pageNumber: $pageNumber) {
      url
      expiresAt
    }
  }
`;
