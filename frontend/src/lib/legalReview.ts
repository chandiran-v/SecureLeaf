export const DRAFT_NOTICE = 'Draft template — not legal advice. Review before enabling live payments.';

/** True once the owner has had the legal text reviewed and set VITE_LEGAL_REVIEWED=true at build time. */
export function legalReviewed(): boolean {
  return import.meta.env.VITE_LEGAL_REVIEWED === 'true';
}
