import { Link } from 'react-router-dom';
import { useQuery } from '@apollo/client';
import AppLayout from '../../components/layout/AppLayout';
import { MY_LIBRARY } from '../../graphql/queries/commerce.queries';
import type { Entitlement } from '../../types';

/**
 * /library — everything the buyer owns (PAY-09, LIB-01/02).
 *
 * Each row is an ENTITLEMENT, not a product: the access grant is the thing the buyer owns.
 * That's why a product the creator later unpublished still shows up here — the grant
 * outlives the listing.
 */
export default function LibraryPage() {
  const { data, loading, error, refetch } = useQuery<{ myLibrary: Entitlement[] }>(MY_LIBRARY, {
    fetchPolicy: 'cache-and-network',
  });
  const items = data?.myLibrary ?? [];

  return (
    <AppLayout>
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <div className="mb-8">
          <h1 className="text-2xl font-bold text-gray-900">My Library</h1>
          <p className="text-gray-500 text-sm mt-1">Everything you've bought — yours to read, never to download.</p>
        </div>

        {loading && items.length === 0 ? (
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3" aria-busy="true">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-40 rounded-xl bg-gray-100 animate-pulse" />
            ))}
          </div>
        ) : error ? (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700" role="alert">
            Failed to load your library.{' '}
            <button onClick={() => refetch()} className="underline font-medium">Retry</button>
          </div>
        ) : items.length === 0 ? (
          <div className="text-center py-20" role="status">
            <h2 className="text-lg font-semibold text-gray-900 mb-1">Your library is empty</h2>
            <p className="text-gray-500 text-sm mb-6">Products you buy will appear here.</p>
            <Link
              to="/marketplace"
              className="inline-flex px-4 py-2.5 bg-emerald-600 text-white text-sm font-semibold rounded-lg hover:bg-emerald-700 transition-colors"
            >
              Browse the marketplace
            </Link>
          </div>
        ) : (
          <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((item) => (
              <li
                key={item.id}
                className="flex gap-4 bg-white rounded-xl border border-gray-200 p-4 shadow-sm hover:shadow-md transition-shadow"
              >
                <div className="w-20 h-28 rounded-lg bg-gradient-to-br from-emerald-50 to-teal-100 flex-shrink-0 overflow-hidden">
                  {item.product.thumbnailUrl && (
                    <img src={item.product.thumbnailUrl} alt="" className="w-full h-full object-cover" />
                  )}
                </div>
                <div className="flex flex-col min-w-0 flex-1">
                  <p className="text-xs text-emerald-600 font-medium">{item.product.category.name}</p>
                  <h2 className="text-sm font-semibold text-gray-900 line-clamp-2">{item.product.title}</h2>
                  <p className="text-xs text-gray-400 mb-auto">by {item.product.creator.displayName}</p>
                  <p className="text-xs text-gray-400 mt-2">
                    Purchased {new Date(item.purchasedAt).toLocaleDateString('en-IN', { dateStyle: 'medium' })}
                  </p>
                  <Link
                    to={`/read/${item.product.id}`}
                    className="mt-2 text-center text-xs py-1.5 rounded-lg bg-emerald-600 text-white font-medium hover:bg-emerald-700 transition-colors"
                  >
                    Read
                  </Link>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </AppLayout>
  );
}
