import { Routes, Route, Navigate } from 'react-router-dom';
import { ApolloProvider } from '@apollo/client';
import { GoogleOAuthProvider } from '@react-oauth/google';
import client from './graphql/apolloClient';
import LoginPage from './pages/auth/LoginPage';
import RegisterPage from './pages/auth/RegisterPage';
import ForgotPasswordPage from './pages/auth/ForgotPasswordPage';
import ResetPasswordPage from './pages/auth/ResetPasswordPage';
import ProtectedRoute from './components/layout/ProtectedRoute';
import BecomeCreatorPage from './pages/creator/BecomeCreatorPage';
import CreatorDashboardPage from './pages/creator/CreatorDashboardPage';
import UploadProductPage from './pages/creator/UploadProductPage';
import MarketplacePage from './pages/marketplace/MarketplacePage';
import ProductDetailPage from './pages/marketplace/ProductDetailPage';
import CheckoutPage from './pages/buyer/CheckoutPage';
import LibraryPage from './pages/buyer/LibraryPage';
import ReaderPage from './pages/viewer/ReaderPage';
import AdminDashboardPage from './pages/admin/AdminDashboardPage';
import AdminUsersPage from './pages/admin/AdminUsersPage';
import AdminProductsPage from './pages/admin/AdminProductsPage';
import AdminAuditLogPage from './pages/admin/AdminAuditLogPage';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';

function App() {
  return (
    <ApolloProvider client={client}>
      <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
        <Routes>
          {/* Public routes */}
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />

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

          {/* Buyer routes (Phase 4) */}
          <Route
            path="/checkout/:orderId"
            element={
              <ProtectedRoute>
                <CheckoutPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/library"
            element={
              <ProtectedRoute>
                <LibraryPage />
              </ProtectedRoute>
            }
          />

          {/* Secure DRM reader (Phase 05B) — any logged-in user; the server checks entitlement */}
          <Route
            path="/read/:productId"
            element={
              <ProtectedRoute>
                <ReaderPage />
              </ProtectedRoute>
            }
          />

          {/* Admin panel (Phase 8) — every route requires the ADMIN role. */}
          <Route
            path="/admin"
            element={
              <ProtectedRoute requiredRole="ADMIN">
                <AdminDashboardPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/admin/users"
            element={
              <ProtectedRoute requiredRole="ADMIN">
                <AdminUsersPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/admin/products"
            element={
              <ProtectedRoute requiredRole="ADMIN">
                <AdminProductsPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/admin/audit-log"
            element={
              <ProtectedRoute requiredRole="ADMIN">
                <AdminAuditLogPage />
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
