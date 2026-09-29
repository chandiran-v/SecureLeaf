import { gql } from '@apollo/client';

// Phase 09C — creator payouts, statements and buyer receipts.

const PAYOUT_FIELDS = `
  id
  creatorId
  creatorName
  creatorEmail
  amountPaise
  status
  grossRevenuePaise
  platformFeePaise
  netPayoutPaise
  payoutMethod
  payoutDestination
  payoutReference
  requestedAt
  processedAt
  notes
`;

export const CREATOR_BALANCE = gql`
  query CreatorBalance {
    creatorBalance {
      availablePaise
      pendingPaise
      lifetimeEarningsPaise
      paidOutPaise
    }
  }
`;

export const MY_PAYOUTS = gql`
  query MyPayouts {
    myPayouts {
      ${PAYOUT_FIELDS}
    }
  }
`;

export const MY_PAYOUT_DETAILS = gql`
  query MyPayoutDetails {
    myPayoutDetails {
      payoutUpi
      payoutEmail
    }
  }
`;

export const CREATOR_STATEMENT = gql`
  query CreatorStatement($month: String!) {
    creatorStatement(month: $month) {
      month
      grossSalesPaise
      refundsPaise
      platformFeePaise
      netEarningsPaise
      payoutsPaise
      lines {
        date
        type
        description
        grossPaise
        feePaise
        netPaise
      }
    }
  }
`;

export const ADMIN_PAYOUTS = gql`
  query AdminPayouts($status: PayoutStatus, $page: Int, $size: Int) {
    adminPayouts(status: $status, page: $page, size: $size) {
      content {
        ${PAYOUT_FIELDS}
      }
      totalElements
      totalPages
      pageNumber
    }
  }
`;

export const ORDER_RECEIPT = gql`
  query OrderReceipt($orderId: ID!) {
    orderReceipt(orderId: $orderId) {
      orderId
      status
      purchasedAt
      productTitle
      creatorName
      amountPaise
      paymentIdMasked
      paymentMode
    }
  }
`;
