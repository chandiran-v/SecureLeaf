import { gql } from '@apollo/client';

// D1 — deviceFingerprint lets the (Phase 05B) UI show "opened on another device" instead of a
// bare error when this call evicts an older session (D3: last writer wins).
export const START_VIEWER_SESSION = gql`
  mutation StartViewerSession($productId: ID!, $deviceFingerprint: String!) {
    startViewerSession(productId: $productId, deviceFingerprint: $deviceFingerprint) {
      sessionId
      sessionToken
      productId
      pageCount
      heartbeatIntervalSeconds
      expiresAt
    }
  }
`;

// D4 — call every `heartbeatIntervalSeconds`. A SUPERSEDED/EXPIRED status means the viewer UI
// must stop rendering pages; the session is no longer valid, even though this call itself
// never errors.
export const VIEWER_HEARTBEAT = gql`
  mutation ViewerHeartbeat($sessionToken: String!) {
    viewerHeartbeat(sessionToken: $sessionToken) {
      status
      expiresAt
    }
  }
`;

export const END_VIEWER_SESSION = gql`
  mutation EndViewerSession($sessionToken: String!) {
    endViewerSession(sessionToken: $sessionToken)
  }
`;
