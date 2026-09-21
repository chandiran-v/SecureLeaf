import { Routes, Route, Navigate } from 'react-router-dom';
import { ApolloProvider } from '@apollo/client';
import { GoogleOAuthProvider } from '@react-oauth/google';
import client from './graphql/apolloClient';
import LoginPage from './pages/auth/LoginPage';
import RegisterPage from './pages/auth/RegisterPage';
import ProtectedRoute from './components/layout/ProtectedRoute';
import AppLayout from './components/layout/AppLayout';
import BecomeCreatorPage from './pages/creator/BecomeCreatorPage';
import CreatorDashboardPage from './pages/creator/CreatorDashboardPage';
import UploadProductPage from './pages/creator/UploadProductPage';

/** Temporary marketplace placeholder — replaced in Phase 3 */
function MarketplacePlaceholder() {
  return (
    <AppLayout>
      <div className="max-w-7xl mx-auto px-4 py-16 text-center">
        <h1 className="text-3xl font-bold text-gray-900">SecureLeaf Marketplace</h1>
        <p className="mt-3 text-gray-500 max-w-md mx-auto">
          Product listings are coming in Phase 3. Use the Creator Dashboard to upload your first document.
        </p>
      </div>
    </AppLayout>
  );
}

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';

function App() {
  return (
    <ApolloProvider client={client}>
      <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
        <Routes>
          {/* Public routes */}
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />

          {/* Protected routes — require valid JWT */}
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <MarketplacePlaceholder />
              </ProtectedRoute>
            }
          />
          <Route
            path="/marketplace"
            element={
              <ProtectedRoute>
                <MarketplacePlaceholder />
              </ProtectedRoute>
            }
          />
          <Route
            path="/become-creator"
            element={
              <ProtectedRoute>
                <BecomeCreatorPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/creator"
            element={
              <ProtectedRoute requiredRole="CREATOR">
                <CreatorDashboardPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/creator/upload"
            element={
              <ProtectedRoute requiredRole="CREATOR">
                <UploadProductPage />
              </ProtectedRoute>
            }
          />

          {/* Fallback */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </GoogleOAuthProvider>
    </ApolloProvider>
  );
}

export default App;
