/**
 * Legal pages are DATA, not markup (Phase 09C D6): a document is a list of sections, each with
 * paragraphs and/or bullets, and one component renders them as text. Plain strings only — nothing
 * here is ever injected as HTML, so there is no `dangerouslySetInnerHTML` and no XSS surface,
 * and a lawyer can edit the wording without touching JSX.
 */
export interface LegalSection {
  heading: string;
  paragraphs?: string[];
  bullets?: string[];
}

export interface LegalDocument {
  title: string;
  /** One-line summary shown under the title. */
  summary: string;
  /** Business details are placeholders until Phase 18 — see BUSINESS. */
  sections: LegalSection[];
}

/** Placeholders replaced with real company details in Phase 18 (Razorpay activation / KYC). */
export const BUSINESS = {
  legalName: '[Legal business name — to be added]',
  address: '[Registered address — to be added]',
} as const;

/** Days a buyer can ask for a refund, and creators' earnings stay on hold. One number, used everywhere. */
export const REFUND_WINDOW_DAYS = 7;
