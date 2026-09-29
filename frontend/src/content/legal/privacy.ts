import { BUSINESS, type LegalDocument } from './types';

export const privacy: LegalDocument = {
  title: 'Privacy Policy',
  summary: 'Exactly what we store about you, why, who else sees it, and how to have it deleted.',
  sections: [
    {
      heading: '1. Who is responsible',
      paragraphs: [
        `${BUSINESS.legalName}, ${BUSINESS.address}, is the data fiduciary for the personal data described here. We process it in line with India's Digital Personal Data Protection Act, 2023 (DPDP Act) and other applicable law.`,
      ],
    },
    {
      heading: '2. What we store',
      bullets: [
        'Account data: your email address, display name, a hashed password (or your Google account identifier if you sign in with Google), your roles (buyer, creator) and account status.',
        'Creator data: for creators, the bio and the UPI id or payout email you give us so we can pay you.',
        'Purchase records: which products you bought, the amount, the date, the order and the payment provider\'s payment id. We never see or store your card or UPI credentials; the payment provider handles those.',
        'Viewer access logs: each time you read a document we record your IP address, your browser\'s user agent, the pages you viewed and when. We use this to enforce one active reading session per purchase, detect misuse and investigate leaks.',
        'Watermark: every page you view is stamped with your account email so a leaked copy can be traced back to you. The stamped image is generated on request and is not kept as a separate record about you.',
        'Notifications: the in-app notifications and emails we send you about purchases, sales, refunds and payouts.',
        'Technical data: server logs (with a request id) and, if enabled, error reports, for security and debugging.',
      ],
    },
    {
      heading: '3. Why we use it',
      bullets: [
        'To run your account, deliver what you bought and pay creators.',
        'To protect creators\' content (access logs, watermarking) and to prevent fraud and abuse.',
        'To meet legal, tax and accounting obligations.',
        'To contact you about your orders and account.',
      ],
    },
    {
      heading: '4. Who else processes your data',
      paragraphs: ['We share data only with the service providers we need to run SecureLeaf:'],
      bullets: [
        'Razorpay: payment processing (your order amount and email, plus whatever you enter on their checkout).',
        'Brevo: sending our transactional emails (your email address and the message).',
        'Sentry: error monitoring, when enabled (technical error details, which we try to keep free of personal data).',
        'Oracle Cloud: the servers and storage where SecureLeaf and its database run.',
      ],
    },
    {
      heading: '5. How long we keep it',
      bullets: [
        'Account data: until you ask us to delete your account.',
        'Purchase and payout records: kept for as long as tax and accounting law requires, even after an account is deleted, because creators\' earnings and audit trails depend on them.',
        'Viewer access logs: kept for as long as needed to investigate misuse of a document, and then removed or anonymised.',
        'Notifications and server logs: kept for a limited period and then removed.',
      ],
    },
    {
      heading: '6. Your rights and how to delete your data',
      paragraphs: [
        'You may ask us to access, correct or delete your personal data, or to withdraw consent, at any time. Email us at the address on the Contact page from the email address of your account. We will confirm your identity and respond within a reasonable time. Some records must be kept by law, as described above.',
        'You can also complain to the Data Protection Board of India if you believe we have not handled your data properly.',
      ],
    },
    {
      heading: '7. Security and changes',
      paragraphs: [
        'We protect data with encryption in transit, hashed passwords, access controls and audit logging, but no system is perfectly secure. We will update this policy if what we do changes and show the date of the latest version here.',
      ],
    },
  ],
};
