import type { ReactNode } from 'react';
import AppLayout from '../layout/AppLayout';
import type { LegalDocument } from '../../content/legal/types';
import { DRAFT_NOTICE, legalReviewed } from '../../lib/legalReview';

/**
 * Renders a {@link LegalDocument} (Phase 09C D6). Text only: every string is rendered as a React
 * text node, never as HTML. The draft banner shows until VITE_LEGAL_REVIEWED=true.
 */
export default function LegalPage({ doc, children }: { doc: LegalDocument; children?: ReactNode }) {
  return (
    <AppLayout>
      <article className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        {!legalReviewed() && (
          <p role="note" className="no-print mb-6 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm font-medium text-amber-900">
            {DRAFT_NOTICE}
          </p>
        )}
        <h1 className="text-3xl font-bold text-gray-900">{doc.title}</h1>
        <p className="mt-2 text-gray-500">{doc.summary}</p>

        {children}

        {doc.sections.map((section) => (
          <section key={section.heading} className="mt-8">
            <h2 className="text-lg font-semibold text-gray-900">{section.heading}</h2>
            {section.paragraphs?.map((p) => (
              <p key={p} className="mt-3 text-sm leading-relaxed text-gray-700">{p}</p>
            ))}
            {section.bullets && (
              <ul className="mt-3 list-disc space-y-2 pl-5 text-sm leading-relaxed text-gray-700">
                {section.bullets.map((b) => <li key={b}>{b}</li>)}
              </ul>
            )}
          </section>
        ))}
      </article>
    </AppLayout>
  );
}
