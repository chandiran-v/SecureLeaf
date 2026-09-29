import { BUSINESS, REFUND_WINDOW_DAYS, type LegalDocument } from './types';

export const terms: LegalDocument = {
  title: 'Terms of Service',
  summary: 'The rules for buying and selling on SecureLeaf.',
  sections: [
    {
      heading: '1. Who we are',
      paragraphs: [
        `SecureLeaf ("we", "us") is an online marketplace where creators sell digital documents and buyers read them in a protected viewer. The service is operated by ${BUSINESS.legalName}, ${BUSINESS.address}.`,
        'By creating an account, buying or selling on SecureLeaf you agree to these terms.',
      ],
    },
    {
      heading: '2. Accounts',
      bullets: [
        'You must give accurate details and keep your password secret.',
        'You are responsible for everything done through your account.',
        'We may suspend an account that breaks these terms or the law.',
      ],
    },
    {
      heading: '3. Buying',
      paragraphs: [
        'A purchase gives you a personal, non-transferable licence to read the document in the SecureLeaf viewer. You are buying access, not a copy: documents cannot be downloaded, and you must not try to copy, capture, redistribute or resell them.',
        'Every page you view is watermarked with your account email. If a document is leaked we can trace it back to the account that viewed it.',
        `Prices are shown in Indian rupees. Refunds are described in our Refund Policy (${REFUND_WINDOW_DAYS} days).`,
      ],
    },
    {
      heading: '4. Selling (creator terms)',
      bullets: [
        'You confirm that you own, or hold the rights to sell, everything you upload, and that it does not infringe anyone else\'s rights or break the law.',
        'SecureLeaf keeps a 10% commission on each sale; you receive the remaining 90% of the sale price. Payment-gateway charges are paid by SecureLeaf and are not deducted from your share.',
        `Earnings from a sale are held for ${REFUND_WINDOW_DAYS} days (the refund window) before they can be paid out. Earnings from a sale that is refunded are not paid.`,
        'Payouts are requested from your dashboard (minimum ₹100, one open request at a time) and are sent manually to the UPI id or email you provide. You are responsible for the accuracy of those details and for any taxes you owe.',
        'We may take a product down at any time, for example after a copyright complaint or a breach of these terms. People who already bought it keep their access unless the purchase is refunded.',
      ],
    },
    {
      heading: '5. Acceptable use',
      paragraphs: [
        'Do not attempt to bypass the viewer\'s protections, scrape the service, upload malware, or use SecureLeaf for anything unlawful. We may end sessions and suspend accounts that do.',
      ],
    },
    {
      heading: '6. Liability',
      paragraphs: [
        'The service is provided "as is". To the extent the law allows, our liability for any claim is limited to the amount you paid us for the purchase the claim relates to.',
      ],
    },
    {
      heading: '7. Changes and contact',
      paragraphs: [
        'We may update these terms; continued use after a change means you accept it. Questions? See the Contact page.',
      ],
    },
  ],
};
