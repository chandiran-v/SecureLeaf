import AppLayout from '../../components/layout/AppLayout';
import AdminNav from '../../components/admin/AdminNav';
import { useAdminAnalytics } from '../../hooks/useAdminAnalytics';

const WINDOW_OPTIONS = [7, 30, 90];

/** paise (integer) → a display string like "₹499". ₹0 is shown as "₹0" here, since these are
 *  aggregate totals (an amount), not a single product's price ("Free"). */
function formatRupees(paise: number): string {
  return '₹' + (paise / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

/** D6 — platform analytics: stat cards, a 7/30/90-day window selector, and the top-5 products
 *  table for that window. */
export default function AdminDashboardPage() {
  const { stats, loading, error, days, setDays } = useAdminAnalytics();

  return (
    <AppLayout>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <div className="flex items-center justify-between mb-2">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Admin Dashboard</h1>
            <p className="text-gray-500 text-sm mt-1">Platform-wide activity and moderation.</p>
          </div>
        </div>
        <AdminNav />

        {loading && !stats ? (
          <div className="flex items-center justify-center py-20">
            <svg className="animate-spin h-6 w-6 text-emerald-500" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
          </div>
        ) : error ? (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
            Failed to load platform stats.
          </div>
        ) : stats ? (
          <>
            {/* ── Window selector ── */}
            <div className="flex items-center gap-2 mb-6">
              <span className="text-sm text-gray-500">Window:</span>
              {WINDOW_OPTIONS.map((option) => (
                <button
                  key={option}
                  onClick={() => setDays(option)}
                  aria-pressed={days === option}
                  className={`px-3 py-1.5 text-sm rounded-lg border transition-colors ${
                    days === option
                      ? 'bg-emerald-600 border-emerald-600 text-white'
                      : 'border-gray-200 text-gray-600 hover:bg-gray-50'
                  }`}
                >
                  {option} days
                </button>
              ))}
            </div>

            {/* ── All-time stat cards ── */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
              {[
                { label: 'Total Users', value: stats.totalUsers },
                { label: 'Creators', value: stats.totalCreators },
                { label: 'Live Products', value: stats.totalLiveProducts },
                { label: 'Orders (all-time)', value: stats.completedOrdersAllTime },
              ].map((stat) => (
                <div key={stat.label} className="rounded-xl border bg-white border-gray-200 px-5 py-4">
                  <p className="text-xs text-gray-500 mb-1">{stat.label}</p>
                  <p className="text-2xl font-bold text-gray-900">{stat.value}</p>
                </div>
              ))}
            </div>

            {/* ── Windowed sales figures ── */}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-8">
              <div className="rounded-xl border px-5 py-4 bg-gradient-to-br from-emerald-50 to-teal-50 border-emerald-200">
                <p className="text-xs text-gray-500 mb-1">Orders (last {stats.windowDays}d)</p>
                <p className="text-2xl font-bold text-gray-900">{stats.completedOrdersWindow}</p>
              </div>
              <div className="rounded-xl border px-5 py-4 bg-gradient-to-br from-emerald-50 to-teal-50 border-emerald-200">
                <p className="text-xs text-gray-500 mb-1">Gross sales (last {stats.windowDays}d)</p>
                <p className="text-2xl font-bold text-gray-900">{formatRupees(stats.grossSalesPaiseWindow)}</p>
              </div>
              <div className="rounded-xl border px-5 py-4 bg-gradient-to-br from-emerald-50 to-teal-50 border-emerald-200">
                <p className="text-xs text-gray-500 mb-1">Platform fees (last {stats.windowDays}d)</p>
                <p className="text-2xl font-bold text-gray-900">{formatRupees(stats.platformFeePaiseWindow)}</p>
              </div>
            </div>

            {/* ── Top products table ── */}
            <h2 className="text-sm font-semibold text-gray-900 mb-3">
              Top products (last {stats.windowDays} days)
            </h2>
            {stats.topProducts.length === 0 ? (
              <p className="text-sm text-gray-500">No sales in this window yet.</p>
            ) : (
              <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                <table className="w-full text-left" aria-label="Top products">
                  <thead>
                    <tr className="border-b border-gray-100 bg-gray-50/60">
                      {['Product', 'Sales', 'Gross sales'].map((h) => (
                        <th key={h} className="py-3 px-6 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {stats.topProducts.map((product) => (
                      <tr key={product.productId}>
                        <td className="py-3 px-6 text-sm font-medium text-gray-900">{product.title}</td>
                        <td className="py-3 px-6 text-sm text-gray-600">{product.salesCount}</td>
                        <td className="py-3 px-6 text-sm text-gray-600">{formatRupees(product.grossSalesPaise)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        ) : null}
      </div>
    </AppLayout>
  );
}
