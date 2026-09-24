import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import type { UserRole } from '../../types';

interface ProtectedRouteProps {
  children: React.ReactNode;
  /** If set, the user must have this role in addition to being authenticated. */
  requiredRole?: UserRole;
}

/**
 * Route guard — redirects unauthenticated users to /login.
 * Preserves the intended destination in location state so the
 * login page can redirect back after successful authentication.
 *
 * If requiredRole is set and the user lacks that role:
 *   CREATOR → redirects to /become-creator (the user knows what to do)
 *   others  → redirects to / (home)
 */
export default function ProtectedRoute({ children, requiredRole }: ProtectedRouteProps) {
  const { isAuthenticated, user } = useAuthStore();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (requiredRole && !user?.roles.includes(requiredRole)) {
    const redirectTo = requiredRole === 'CREATOR' ? '/become-creator' : '/';
    return <Navigate to={redirectTo} replace />;
  }

  return <>{children}</>;
}
