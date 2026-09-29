import { BUSINESS, type LegalDocument } from './types';

/** Contact is the one page with a live value (the support email) — the page component adds it. */
export const contact: LegalDocument = {
  title: 'Contact us',
  summary: 'Questions, refund requests, deletion requests or takedown notices — email us.',
  sections: [
    {
      heading: 'What to write to us about',
      bullets: [
        'Refund requests (include your order number).',
        'Payment problems (include the payment id from your receipt).',
        'Requests to access, correct or delete your personal data.',
        'Copyright complaints and takedown notices.',
        'Anything else.',
      ],
    },
    {
      heading: 'Business details',
      paragraphs: [`Operated by ${BUSINESS.legalName}. Registered address: ${BUSINESS.address}.`],
    },
  ],
};
