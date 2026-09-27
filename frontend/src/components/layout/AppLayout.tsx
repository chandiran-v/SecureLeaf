import { useEffect, useState } from 'react';
import { useNavigate, Link, NavLink, useLocation } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { useAuthStore } from '../../store/authStore';
import NotificationBell from './NotificationBell';

interface NavItem {
  to: string;
  label: string;
  /** Call-to-action styling (e.g. "Become a Creator") instead of a plain link. */
  accent?: boolean;
}

/**
 * Application shell layout — header/nav with role-aware links.
 *
 * Role awareness:
 * - Every user sees: Marketplace link, User name, Logout
 * - CREATOR users also see: Creator Dashboard link
 * - Non-creator users see: "Become a Creator" link
 * - ADMIN users also see: Admin link
 *
 * Responsive: from `md` up the links sit inline in the header. Below `md` they collapse behind
 * a menu button into a drop-down panel (with the user's name and Log out), so a phone can still
 * reach My Library and the Creator Dashboard. The notification bell stays visible in the header.
 */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { logout } = useAuth();
  const { user, isAuthenticated } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);

  const isCreator = user?.roles.includes('CREATOR') ?? false;
  const isAdmin = user?.roles.includes('ADMIN') ?? false;

  // One list drives both the desktop links and the mobile menu, so they can't drift apart.
  const navItems: NavItem[] = [{ to: '/marketplace', label: 'Marketplace' }];
  if (isAuthenticated) {
    navItems.push({ to: '/library', label: 'My Library' });
    navItems.push(
      isCreator
        ? { to: '/creator', label: 'Creator Dashboard' }
        : { to: '/become-creator', label: 'Become a Creator', accent: true }
    );
    if (isAdmin) navItems.push({ to: '/admin', label: 'Admin' });
  }

  // Close the mobile menu whenever the route changes (e.g. after tapping a link).
  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  // Escape closes it too, like any other menu.
  useEffect(() => {
    if (!menuOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [menuOpen]);

  const handleLogout = async () => {
    setMenuOpen(false);
    await logout();
    navigate('/login');
  };

  const desktopLinkClass = (item: NavItem) => ({ isActive }: { isActive: boolean }) =>
    `px-3 py-2 text-sm font-medium rounded-lg transition-colors ${
      item.accent
        ? 'text-emerald-600 hover:bg-emerald-50'
        : isActive
          ? 'bg-gray-100 text-gray-900'
          : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
    }`;

  const mobileLinkClass = (item: NavItem) => ({ isActive }: { isActive: boolean }) =>
    `block rounded-lg px-3 py-3 text-base font-medium transition-colors ${
      item.accent
        ? 'text-emerald-600 hover:bg-emerald-50'
        : isActive
          ? 'bg-emerald-50 text-emerald-700'
          : 'text-gray-700 hover:bg-gray-100'
    }`;

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <header className="sticky top-0 z-50 bg-white border-b border-gray-200 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          {/* Logo */}
          <Link to="/" className="flex items-center gap-2 group">
            <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-emerald-500 to-teal-600 flex items-center justify-center shadow-sm group-hover:shadow transition-shadow">
              <svg className="w-4 h-4 text-white" fill="currentColor" viewBox="0 0 24 24">
                <path d="M4 4h6v6H4V4zm10 0h6v6h-6V4zM4 14h6v6H4v-6zm10 3a3 3 0 106 0 3 3 0 00-6 0z" />
              </svg>
            </div>
            <span className="font-semibold text-gray-900 tracking-tight text-lg">
              Secure<span className="text-emerald-600">Leaf</span>
            </span>
          </Link>

          {/* Nav links — desktop */}
          <nav className="hidden md:flex items-center gap-1" aria-label="Main">
            {navItems.map((item) => (
              <NavLink key={item.to} to={item.to} className={desktopLinkClass(item)}>
                {item.label}
              </NavLink>
            ))}
          </nav>

          {/* User menu */}
          <div className="flex items-center gap-1 sm:gap-3">
            {isAuthenticated ? (
              <>
                <NotificationBell />
                <span className="text-sm text-gray-500 hidden md:block">
                  {user?.displayName}
                </span>
                <button
                  id="logout-button"
                  onClick={handleLogout}
                  className="hidden md:flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-gray-600 rounded-lg hover:bg-gray-100 hover:text-gray-900 transition-colors"
                >
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                      d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                  </svg>
                  <span>Log out</span>
                </button>
              </>
            ) : (
              <Link
                to="/login"
                id="login-link"
                className="px-3 py-2 text-sm font-medium text-emerald-600 rounded-lg hover:bg-emerald-50 transition-colors"
              >
                Log in
              </Link>
            )}

            {/* Menu button — mobile only */}
            <button
              type="button"
              onClick={() => setMenuOpen((open) => !open)}
              aria-label={menuOpen ? 'Close menu' : 'Open menu'}
              aria-expanded={menuOpen}
              aria-controls="mobile-menu"
              className="md:hidden inline-flex h-10 w-10 items-center justify-center rounded-lg text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors"
            >
              <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                {menuOpen ? (
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                ) : (
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
                )}
              </svg>
            </button>
          </div>
        </div>

        {/* Nav links — mobile drop-down. Only rendered while open, so each link exists once in the DOM. */}
        {menuOpen && (
          <nav id="mobile-menu" aria-label="Mobile" className="md:hidden border-t border-gray-100 bg-white px-4 pb-4 pt-2 shadow-lg">
            <div className="flex flex-col gap-1">
              {navItems.map((item) => (
                <NavLink key={item.to} to={item.to} onClick={() => setMenuOpen(false)} className={mobileLinkClass(item)}>
                  {item.label}
                </NavLink>
              ))}
            </div>

            {isAuthenticated && (
              <div className="mt-3 flex items-center justify-between border-t border-gray-100 pt-3">
                <span className="truncate px-3 text-sm text-gray-500">{user?.displayName}</span>
                <button
                  onClick={handleLogout}
                  className="flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-100 transition-colors"
                >
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                      d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                  </svg>
                  Log out
                </button>
              </div>
            )}
          </nav>
        )}
      </header>

      {/* Tap outside the open menu to close it. */}
      {menuOpen && (
        <div
          className="fixed inset-0 top-16 z-40 bg-gray-900/20 md:hidden"
          aria-hidden="true"
          onClick={() => setMenuOpen(false)}
        />
      )}

      {/* ── Main content ────────────────────────────────────────────────────── */}
      <main className="flex-1">
        {children}
      </main>
    </div>
  );
}
