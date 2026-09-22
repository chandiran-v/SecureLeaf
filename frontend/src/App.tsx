import { Routes, Route, Navigate } from 'react-router-dom';
import { ApolloProvider } from '@apollo/client';
import { GoogleOAuthProvider } from '@react-oauth/google';
import client from './graphql/apolloClient';
import LoginPage from './pages/auth/LoginPage';
import RegisterPage from './pages/auth/RegisterPage';
import ProtectedRoute from './components/layout/ProtectedRoute';
import BecomeCreatorPage from './pages/creator/BecomeCreatorPage';
import CreatorDashboardPage from './pages/creator/CreatorDashboardPage';
import UploadProductPage from './pages/creator/UploadProductPage';
import MarketplacePage from './pages/marketplace/MarketplacePage';
import ProductDetailPage from './pages/marketplace/ProductDetailPage';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';

function App() {
  return (
    <ApolloProvider client={client}>
      <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
        <Routes>
          {/* Public routes */}
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />

          {/* Public marketplace — browsing and free preview need no auth (Phase 3) */}
          <Route path="/" element={<MarketplacePage />} />
          <Route path="/marketplace" element={<MarketplacePage />} />
          <Route path="/product/:id" element={<ProductDetailPage />} />

          {/* Protected routes — require valid JWT */}
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
