import { gql } from '@apollo/client';

// D5 — mints one signed, single-use tile URL, valid ~30s. Re-query this for every page turn;
// Phase 16, D5 — `variant` is signed into the url. Never reuse a `url` (the tile endpoint enforces single-use server-side and 403s a replay).
export const VIEWER_PAGE_URL = gql`
  query ViewerPageUrl($sessionToken: String!, $pageNumber: Int!, $variant: String!) {
    viewerPageUrl(sessionToken: $sessionToken, pageNumber: $pageNumber, variant: $variant) {
      url
      expiresAt
      # V8 — clickable areas on this page, overlaid on the canvas by ViewerCanvas.
      links {
        left
        top
        width
        height
        type
        url
        targetPage
      }
    }
  }
`;
