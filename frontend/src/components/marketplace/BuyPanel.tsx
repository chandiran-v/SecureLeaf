import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { useBuyProduct } from '../../hooks/useBuyProduct';
import { formatPrice } from '../../lib/formatPrice';
import type { Product } from '../../types';

const primary =
  'w-full py-3 rounded-lg font-semibold text-sm transition-all active:scale-[0.98] ' +
  'bg-gradient-to-r from-emerald-600 to-teal-600 text-white shadow-sm hover:from-emerald-700 hover:to-teal-700 ' +
  'disabled:opacity-60 disabled:cursor-not-allowed';

/**
 * The product page's call to action. Five states, checked in this order:
 *   owned → "In your library"   ·   your own product → disabled
 *   logged out → "Log in to buy" ·   free → "Get for free"   ·   paid → "Buy for ₹X"
 * The UI checks are for UX only — the server enforces every one of them again (D12).
 */
export default function BuyPanel({ product }: { product: Product }) {
  const { isAuthenticated, user } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const { buy, loading, error } = useBuyProduct(product.id);

  if (product.ownedByMe) {
    return (
      <Link to="/library" className={`${primary} block text-center`}>
        ✓ In your library
      </Link>
    );
  }

  if (isAuthenticated && user?.id === product.creator.id) {
    return (
      <button disabled className="w-full py-3 rounded-lg bg-gray-100 text-gray-400 font-semibold text-sm cursor-not-allowed">
        This is your product
      </button>
    );
  }

  if (!isAuthenticated) {
    return (
      <button onClick={() => navigate('/login', { state: { from: location } })} className={primary}>
        Log in to buy
      </button>
    );
  }

  const label = product.pricePaise === 0 ? 'Get for free' : `Buy for ${formatPrice(product.pricePaise)}`;

  return (
    <div>
      <button onClick={() => void buy().catch(() => undefined)} disabled={loading} className={primary}>
        {loading ? 'Starting checkout…' : label}
      </button>
      {error && (
        <p className="mt-2 text-sm text-red-600" role="alert">
          {error.graphQLErrors[0]?.message ?? 'Could not start checkout. Please try again.'}
        </p>
      )}
    </div>
  );
}
