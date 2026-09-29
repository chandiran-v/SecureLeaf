import { Link } from 'react-router-dom';
import { FOOTER_LINKS } from '../../content/legal/footerLinks';


/** Site footer (Phase 09C D6) — the legal/info links every public marketplace needs, on every page. */
export default function SiteFooter() {
  return (
    <footer className="no-print border-t border-gray-200 bg-white">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 flex flex-col sm:flex-row items-center justify-between gap-3 text-sm text-gray-500">
        <p>© {new Date().getFullYear()} SecureLeaf</p>
        <nav aria-label="Legal" className="flex flex-wrap items-center justify-center gap-x-5 gap-y-2">
          {FOOTER_LINKS.map((link) => (
            <Link key={link.to} to={link.to} className="hover:text-gray-900 hover:underline">
              {link.label}
            </Link>
          ))}
        </nav>
      </div>
    </footer>
  );
}
