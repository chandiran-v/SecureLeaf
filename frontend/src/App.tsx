import { Routes, Route, Navigate } from 'react-router-dom';
import { ApolloProvider } from '@apollo/client';
import { GoogleOAuthProvider } from '@react-oauth/google';
import client from './graphql/apolloClient';
import LoginPage from './pages/auth/LoginPage';
import RegisterPage from './pages/auth/RegisterPage';
import ProtectedRoute from './components/layout/ProtectedRoute';
import { useAuthStore } from './store/authStore';

/** Minimal home shell — expanded in Phase 2+ */
function HomePage() {
  const user = useAuthStore((s) => s.user);
  const clearAuth = useAuthStore((s) => s.clearAuth);

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white border-b border-gray-200">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 bg-emerald-600 rounded-lg flex items-center justify-center">
              <svg className="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313-12.454z" />
              </svg>
            </div>
            <span className="text-lg font-semibold text-gray-900">SecureLeaf</span>
          </div>

          <div className="flex items-center gap-4">
            <span className="text-sm text-gray-600">
              {user?.displayName}
            </span>
            <button
              onClick={() => {
                clearAuth();
                window.location.href = '/login';
              }}
              className="text-sm text-gray-500 hover:text-gray-700 transition-colors"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-12">
        <div className="text-center">
          <h1 className="text-3xl font-bold text-gray-900">Welcome to SecureLeaf</h1>
          <p className="mt-3 text-gray-500 max-w-md mx-auto">
            You're signed in as <span className="font-medium text-gray-700">{user?.email}</span>.
            The marketplace, creator dashboard, and DRM viewer are coming in future phases.
          </p>
          <div className="mt-6 inline-flex items-center gap-2 rounded-full bg-emerald-50 px-4 py-2 text-sm text-emerald-700">
            <span className="inline-block w-2 h-2 bg-emerald-500 rounded-full animate-pulse" />
            Phase 1 complete — Auth & Security
          </div>
        </div>
      </main>
    </div>
  );
}

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';

function App() {
  return (
    <ApolloProvider client={client}>
      <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <HomePage />
              </ProtectedRoute>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </GoogleOAuthProvider>
    </ApolloProvider>
  );
}

export default App;
