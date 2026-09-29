import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AboutPage, ContactPage, PrivacyPage, RefundPolicyPage, TermsPage } from './LegalPages';
import { DRAFT_NOTICE } from '../../lib/legalReview';
import { usePlatformInfo } from '../../hooks/usePlatformInfo';

vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock('../../components/layout/NotificationBell', () => ({ default: () => null }));
vi.mock('../../hooks/usePlatformInfo');

function renderPage(page: React.ReactElement) {
  vi.mocked(usePlatformInfo).mockReturnValue({ info: { paymentMode: 'TEST', supportEmail: 'help@secureleaf.example' } });
  return render(<MemoryRouter>{page}</MemoryRouter>);
}

afterEach(() => { vi.unstubAllEnvs(); });

describe('legal pages (Phase 09C D6)', () => {
  it.each([
    ['Terms of Service', <TermsPage key="t" />],
    ['Privacy Policy', <PrivacyPage key="p" />],
    ['Refund Policy', <RefundPolicyPage key="r" />],
    ['Contact us', <ContactPage key="c" />],
    ['About SecureLeaf', <AboutPage key="a" />],
  ])('%s renders with the draft banner by default', (title, page) => {
    renderPage(page);
    expect(screen.getByRole('heading', { level: 1, name: title })).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent(DRAFT_NOTICE);
  });

  it('drops the banner once VITE_LEGAL_REVIEWED=true', () => {
    vi.stubEnv('VITE_LEGAL_REVIEWED', 'true');
    renderPage(<TermsPage />);
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
  });

  it('the refund policy says 7 days and that access ends', () => {
    renderPage(<RefundPolicyPage />);
    expect(screen.getAllByText(/7 days/).length).toBeGreaterThan(0);
    expect(screen.getByText(/access to the document ends immediately/i)).toBeInTheDocument();
  });

  it('the privacy policy states what is stored, the processors and deletion', () => {
    renderPage(<PrivacyPage />);
    expect(screen.getByText(/IP address, your browser's user agent, the pages you viewed/i)).toBeInTheDocument();
    expect(screen.getByText(/stamped with your account email/i)).toBeInTheDocument();
    for (const processor of ['Razorpay', 'Brevo', 'Sentry', 'Oracle Cloud']) {
      expect(screen.getByText(new RegExp(`^${processor}:`))).toBeInTheDocument();
    }
    expect(screen.getByText(/Digital Personal Data Protection Act, 2023/i)).toBeInTheDocument();
    expect(screen.getByText(/ask us to access, correct or delete/i)).toBeInTheDocument();
  });

  it('the terms cover creator rights, the 10% commission, the hold and takedowns', () => {
    renderPage(<TermsPage />);
    expect(screen.getByText(/own, or hold the rights to sell/i)).toBeInTheDocument();
    expect(screen.getByText(/10% commission/i)).toBeInTheDocument();
    expect(screen.getByText(/held for 7 days/i)).toBeInTheDocument();
    expect(screen.getByText(/take a product down/i)).toBeInTheDocument();
  });

  it('the contact page shows the configured support email', () => {
    renderPage(<ContactPage />);
    expect(screen.getByRole('link', { name: 'help@secureleaf.example' })).toHaveAttribute('href', 'mailto:help@secureleaf.example');
  });
});
