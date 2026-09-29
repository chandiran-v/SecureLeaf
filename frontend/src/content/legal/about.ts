import type { LegalDocument } from './types';

export const about: LegalDocument = {
  title: 'About SecureLeaf',
  summary: 'A marketplace where creators sell documents and buyers read them, safely.',
  sections: [
    {
      heading: 'What SecureLeaf is',
      paragraphs: [
        'SecureLeaf lets creators upload PDFs and sell them. Buyers read what they bought in a protected, watermarked viewer that does not offer a download, so a creator\'s work is not passed around for free.',
      ],
    },
    {
      heading: 'How it works',
      bullets: [
        'Creators upload a PDF, set a price and publish. SecureLeaf keeps 10%; the creator gets 90%.',
        'Buyers pay through Razorpay and read the document from their library straight away.',
        'Creators request a payout once their earnings have cleared the 7-day refund window.',
      ],
    },
  ],
};
