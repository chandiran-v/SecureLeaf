import { REFUND_WINDOW_DAYS, type LegalDocument } from './types';

export const refundPolicy: LegalDocument = {
  title: 'Refund Policy',
  summary: `Digital goods: refunds are considered on request within ${REFUND_WINDOW_DAYS} days of purchase.`,
  sections: [
    {
      heading: '1. Digital goods',
      paragraphs: [
        'Everything sold on SecureLeaf is a digital document that is delivered instantly, so purchases are not automatically refundable.',
      ],
    },
    {
      heading: `2. Refunds within ${REFUND_WINDOW_DAYS} days`,
      paragraphs: [
        `You can ask for a refund within ${REFUND_WINDOW_DAYS} days of purchase by emailing us (see the Contact page) with your order number and the reason. We decide each request at our discretion, for example when the document could not be opened, is not what was described, or was bought by mistake and barely read.`,
        `After ${REFUND_WINDOW_DAYS} days we can no longer refund a purchase.`,
      ],
    },
    {
      heading: '3. What happens when we refund',
      bullets: [
        'The full amount goes back to your original payment method; your bank usually takes a few business days to show it.',
        'Your access to the document ends immediately: it is removed from your library and can no longer be opened.',
        'The creator\'s earnings from that sale are reversed.',
      ],
    },
    {
      heading: '4. Failed or duplicate payments',
      paragraphs: [
        'If money left your account but you did not receive the document, contact us with the payment id. Confirmed duplicate or failed charges are refunded in full.',
      ],
    },
  ],
};
