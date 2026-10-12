import { Navigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import MarketplacePage from '../../pages/marketplace/MarketplacePage';

/**
 * The site's front door ("/"). Signed-in users land on the marketplace; everyone else lands on
 * the login page, which offers "Continue as guest" → /marketplace. The marketplace itself stays
 * public at /marketplace, so guests (and Razorpay's site review) can still browse and preview.
 */
export default function HomeRoute() {
  const { isAuthenticated } = useAuthStore();
  return isAuthenticated ? <MarketplacePage /> : <Navigate to="/login" replace />;
}
