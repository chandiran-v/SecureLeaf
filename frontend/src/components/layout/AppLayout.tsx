import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { useAuthStore } from '../../store/authStore';
import NotificationBell from './NotificationBell';

/**
 * Application shell layout — header/nav with role-aware links.
 *
 * Role awareness:
 * - Every user sees: Marketplace link, User name, Logout
 * - CREATOR users also see: Creator Dashboard link
 * - Non-creator users see: "Become a Creator" link
 *
 * This replaces the placeholder that was in App.tsx.
 */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { logout } = useAuth();
  const { user, isAuthenticated } = useAuthStore();
  const navigate = useNavigate();

  const isCreator = user?.roles.includes('CREATOR') ?? false;
  const isAdmin = user?.roles.includes('ADMIN') ?? false;

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

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

          {/* Nav links */}
          <nav className="hidden sm:flex items-center gap-1">
            <Link
              to="/marketplace"
              className="px-3 py-2 text-sm font-medium text-gray-600 rounded-lg hover:bg-gray-100 hover:text-gray-900 transition-colors"
            >
              Marketplace
            </Link>
            {isAuthenticated && (
              <Link
                to="/library"
                className="px-3 py-2 text-sm font-medium text-gray-600 rounded-lg hover:bg-gray-100 hover:text-gray-900 transition-colors"
              >
                My Library
              </Link>
            )}
            {isAuthenticated && (
              isCreator ? (
                <Link
                  to="/creator"
                  className="px-3 py-2 text-sm font-medium text-gray-600 rounded-lg hover:bg-gray-100 hover:text-gray-900 transition-colors"
                >
                  Creator Dashboard
                </Link>
              ) : (
                <Link
                  to="/become-creator"
                  className="px-3 py-2 text-sm font-medium text-emerald-600 rounded-lg hover:bg-emerald-50 transition-colors"
                >
                  Become a Creator
                </Link>
              )
            )}
            {isAuthenticated && isAdmin && (
              <Link
                to="/admin"
                className="px-3 py-2 text-sm font-medium text-gray-600 rounded-lg hover:bg-gray-100 hover:text-gray-900 transition-colors"
              >
                Admin
              </Link>
            )}
          </nav>

          {/* User menu */}
          <div className="flex items-center gap-3">
            {isAuthenticated ? (
              <>
                <NotificationBell />
                <span className="text-sm text-gray-500 hidden sm:block">
                  {user?.displayName}
                </span>
                <button
                  id="logout-button"
                  onClick={handleLogout}
                  className="flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-gray-600 rounded-lg hover:bg-gray-100 hover:text-gray-900 transition-colors"
                >
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                      d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                  </svg>
                  <span className="hidden sm:inline">Log out</span>
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
          </div>
        </div>
      </header>

      {/* ── Main content ────────────────────────────────────────────────────── */}
      <main className="flex-1">
        {children}
      </main>
    </div>
  );
}
