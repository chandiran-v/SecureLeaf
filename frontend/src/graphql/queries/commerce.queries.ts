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
