import { gql } from '@apollo/client';

export const REQUEST_PAYOUT = gql`
  mutation RequestPayout($amountPaise: Long!) {
    requestPayout(amountPaise: $amountPaise) {
      id
      status
      amountPaise
    }
  }
`;

export const UPDATE_PAYOUT_DETAILS = gql`
  mutation UpdatePayoutDetails($payoutUpi: String, $payoutEmail: String) {
    updatePayoutDetails(payoutUpi: $payoutUpi, payoutEmail: $payoutEmail) {
      payoutUpi
      payoutEmail
    }
  }
`;

export const APPROVE_PAYOUT = gql`
  mutation ApprovePayout($id: ID!) {
    approvePayout(id: $id) {
      id
      status
    }
  }
`;

export const MARK_PAYOUT_PAID = gql`
  mutation MarkPayoutPaid($id: ID!, $reference: String!) {
    markPayoutPaid(id: $id, reference: $reference) {
      id
      status
      payoutReference
    }
  }
`;

export const REJECT_PAYOUT = gql`
  mutation RejectPayout($id: ID!, $reason: String!) {
    rejectPayout(id: $id, reason: $reason) {
      id
      status
      notes
    }
  }
`;
