import { gql } from '@apollo/client';
import { ORDER_FIELDS } from '../fragments/commerce.fragments';

export const ORDER = gql`
  ${ORDER_FIELDS}
  query Order($id: ID!) {
    order(id: $id) {
      ...OrderFields
    }
  }
`;

export const MY_LIBRARY = gql`
  query MyLibrary {
    myLibrary {
      id
      purchasedAt
      status
      versionNumber
      newEditionAvailable
      product {
        id
        title
        thumbnailUrl
        pageCount
        creator {
          id
          displayName
        }
        category {
          id
          name
          slug
        }
      }
    }
  }
`;

export const CREATOR_EARNINGS = gql`
  query CreatorEarnings {
    creatorEarnings {
      salesCount
      grossSalesPaise
      platformFeePaise
      netEarningsPaise
    }
  }
`;

export const MY_NOTIFICATIONS = gql`
  query MyNotifications {
    myNotifications {
      id
      type
      title
      body
      isRead
      createdAt
    }
  }
`;

// Phase 6, D7 — a fresh, single-use ticket exchanged for one SSE connection.
export const NOTIFICATION_STREAM_TICKET = gql`
  query NotificationStreamTicket {
    notificationStreamTicket
  }
`;

// Phase 09B D8 — public: the demo banner needs it before login.
export const PLATFORM_INFO = gql`
  query PlatformInfo {
    platformInfo {
      paymentMode
      supportEmail
    }
  }
`;
