import { gql } from '@apollo/client';

// Phase 8 (ADMIN-01..04) — admin-only reads. No fragment shared with the public marketplace
// queries: AdminUser/AdminProduct are deliberately separate GraphQL types from User/Product
// (see backend admin.graphqls), so there is nothing to spread here.

export const ADMIN_USERS = gql`
  query AdminUsers($filter: AdminUserFilter, $page: Int, $size: Int) {
    adminUsers(filter: $filter, page: $page, size: $size) {
      content {
        id
        email
        displayName
        roles
        accountStatus
        authProvider
        createdAt
        productCount
        purchaseCount
      }
      totalElements
      totalPages
      pageNumber
    }
  }
`;

export const ADMIN_PRODUCTS = gql`
  query AdminProducts($filter: AdminProductFilter, $page: Int, $size: Int) {
    adminProducts(filter: $filter, page: $page, size: $size) {
      content {
        id
        title
        status
        creator {
          id
          displayName
        }
        pricePaise
        totalSales
        takenDownAt
        takedownReason
        createdAt
      }
      totalElements
      totalPages
      pageNumber
    }
  }
`;

export const PLATFORM_STATS = gql`
  query PlatformStats($days: Int) {
    platformStats(days: $days) {
      totalUsers
      totalCreators
      totalLiveProducts
      completedOrdersAllTime
      grossSalesPaiseAllTime
      platformFeePaiseAllTime
      windowDays
      completedOrdersWindow
      grossSalesPaiseWindow
      platformFeePaiseWindow
      topProducts {
        productId
        title
        salesCount
        grossSalesPaise
      }
    }
  }
`;

export const ADMIN_ACTIONS = gql`
  query AdminActions($page: Int, $size: Int) {
    adminActions(page: $page, size: $size) {
      content {
        id
        action
        targetType
        targetId
        reason
        createdAt
        admin {
          id
          displayName
        }
      }
      totalElements
      totalPages
      pageNumber
    }
  }
`;
