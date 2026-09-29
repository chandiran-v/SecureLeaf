import LegalPage from '../../components/legal/LegalPage';
import { usePlatformInfo } from '../../hooks/usePlatformInfo';
import { terms } from '../../content/legal/terms';
import { privacy } from '../../content/legal/privacy';
import { refundPolicy } from '../../content/legal/refundPolicy';
import { about } from '../../content/legal/about';
import { contact } from '../../content/legal/contact';

export function TermsPage() {
  return <LegalPage doc={terms} />;
}

export function PrivacyPage() {
  return <LegalPage doc={privacy} />;
}

export function RefundPolicyPage() {
  return <LegalPage doc={refundPolicy} />;
}

export function AboutPage() {
  return <LegalPage doc={about} />;
}

/** Contact shows the configured support address (`app.support-email` via platformInfo — D6). */
export function ContactPage() {
  const { info } = usePlatformInfo();
  return (
    <LegalPage doc={contact}>
      <p className="mt-6 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-4 text-sm text-gray-800">
        Email:{' '}
        {info ? (
          <a className="font-semibold text-emerald-700 underline" href={`mailto:${info.supportEmail}`}>
            {info.supportEmail}
          </a>
        ) : (
          <span className="text-gray-500">loading…</span>
        )}
      </p>
    </LegalPage>
  );
}
