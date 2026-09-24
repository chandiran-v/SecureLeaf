import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@apollo/client';
import AppLayout from '../../components/layout/AppLayout';
import StatusBadge from '../../components/ui/StatusBadge';
import { useCreatorProducts } from '../../hooks/useCreatorProducts';
import { CREATOR_EARNINGS } from '../../graphql/queries/commerce.queries';
import type { CreatorEarnings, Product } from '../../types';

// ── Price formatter ───────────────────────────────────────────────────────────

function formatPrice(pricePaise: number): string {
  if (pricePaise === 0) return 'Free';
  return '₹' + (pricePaise / 100).toLocaleString('en-IN', { minimumFractionDigits: 0 });
}

/** Like formatPrice, but ₹0 is "₹0" (an amount), not "Free" (a price). */
function formatRupees(paise: number): string {
  return '₹' + (paise / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

// ── Empty state ───────────────────────────────────────────────────────────────

function EmptyState() {
  return (
    <div className="text-center py-20">
      <div className="w-16 h-16 rounded-2xl bg-emerald-50 flex items-center justify-center mx-auto mb-4">
        <svg className="w-8 h-8 text-emerald-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
            d="M12 4v16m8-8H4" />
        </svg>
      </div>
      <h3 className="text-lg font-semibold text-gray-900 mb-1">No products yet</h3>
      <p className="text-gray-500 text-sm mb-6">Upload your first document to start selling.</p>
      <Link
        to="/creator/upload"
        id="create-first-product-btn"
        className="inline-flex items-center gap-2 px-4 py-2.5 bg-emerald-600 text-white text-sm font-semibold rounded-lg hover:bg-emerald-700 transition-colors"
      >
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
        </svg>
        Upload your first document
      </Link>
    </div>
  );
}

// ── Product row ───────────────────────────────────────────────────────────────

function ProductRow({
  product,
  onUnpublish,
  onDelete,
}: {
  product: Product;
  onUnpublish: (id: string) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}) {
  return (
    <tr className="hover:bg-gray-50 transition-colors">
      <td className="py-4 px-6">
        <div className="flex items-center gap-3">
          {/* Thumbnail */}
          <div className="w-10 h-12 rounded bg-gray-100 flex-shrink-0 overflow-hidden">
            {product.thumbnailUrl ? (
              <img src={product.thumbnailUrl} alt={product.title} className="w-full h-full object-cover" />
            ) : (
              <div className="w-full h-full flex items-center justify-center">
                <svg className="w-5 h-5 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                    d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
                </svg>
              </div>
            )}
          </div>
          <div>
            <p className="text-sm font-medium text-gray-900 line-clamp-1">{product.title}</p>
            <p className="text-xs text-gray-400">{product.category.name}</p>
          </div>
        </div>
      </td>
      <td className="py-4 px-6">
        <StatusBadge status={product.status} />
      </td>
      <td className="py-4 px-6 text-sm text-gray-700 font-medium">
        {formatPrice(product.pricePaise)}
      </td>
      <td className="py-4 px-6 text-sm text-gray-500">
        {product.totalSales} sale{product.totalSales !== 1 ? 's' : ''}
      </td>
      <td className="py-4 px-6">
        <div className="flex items-center gap-2 justify-end">
          {product.status === 'LIVE' && (
            <button
              onClick={() => onUnpublish(product.id)}
              className="text-xs px-3 py-1.5 rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors"
            >
              Unpublish
            </button>
          )}
          {product.status === 'DRAFT' && (
            <Link
              to="/creator/upload"
              state={{ productId: product.id }}
              className="text-xs px-3 py-1.5 rounded-lg border border-emerald-200 text-emerald-700 hover:bg-emerald-50 transition-colors"
            >
              Upload PDF
            </Link>
          )}
          <button
            onClick={() => onDelete(product.id)}
            className="text-xs px-3 py-1.5 rounded-lg border border-red-100 text-red-500 hover:bg-red-50 transition-colors"
          >
            Delete
          </button>
        </div>
      </td>
    </tr>
  );
}

// ── Dashboard page ────────────────────────────────────────────────────────────

/**
 * CreatorDashboardPage
 *
 * Shows all the creator's products in a table with status, price, sales.
 * Automatically polls every 3 seconds when any product is PROCESSING —
 * so the creator sees the status flip to LIVE without refreshing the page.
 */
export default function CreatorDashboardPage() {
  const {
    products,
    productsLoading,
    productsError,
    refetchProducts,
    unpublishProduct,
    deleteProduct,
  } = useCreatorProducts();

  // Auto-poll while any product is processing
  const hasProcessing = products.some((p) => p.status === 'PROCESSING');
  useEffect(() => {
    if (!hasProcessing) return;
    const interval = setInterval(() => refetchProducts(), 3000);
    return () => clearInterval(interval);
  }, [hasProcessing, refetchProducts]);

  // PAY-10 — earnings come from the server, summed from the fee split snapshotted on each
  // order item (D7). The old client-side `totalSales × pricePaise` was wrong the moment a
  // creator changed a price: it re-priced every past sale at today's price.
  const { data: earningsData } = useQuery<{ creatorEarnings: CreatorEarnings }>(CREATOR_EARNINGS, {
    fetchPolicy: 'cache-and-network',
  });
  const earnings = earningsData?.creatorEarnings;

  return (
    <AppLayout>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        {/* ── Page header ── */}
        <div className="flex items-center justify-between mb-8">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Creator Dashboard</h1>
            <p className="text-gray-500 text-sm mt-1">
              Manage your products and track sales
            </p>
          </div>
          <Link
            to="/creator/upload"
            id="upload-product-btn"
            className="inline-flex items-center gap-2 px-4 py-2.5 bg-emerald-600 text-white text-sm font-semibold rounded-lg
                       hover:bg-emerald-700 shadow-sm transition-all active:scale-[0.98]"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
            </svg>
            Upload new product
          </Link>
        </div>

        {/* ── Stats bar ── */}
        {products.length > 0 && (
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
            {[
              { label: 'Total Products', value: products.length, hint: null },
              { label: 'Total Sales', value: earnings?.salesCount ?? '—', hint: null },
              { label: 'Gross Sales', value: earnings ? formatRupees(earnings.grossSalesPaise) : '—', hint: null },
              {
                label: 'Your Earnings',
                value: earnings ? formatRupees(earnings.netEarningsPaise) : '—',
                hint: earnings ? `after ${formatRupees(earnings.platformFeePaise)} platform fee (10%)` : null,
              },
            ].map((stat) => (
              <div
                key={stat.label}
                className={`rounded-xl border px-5 py-4 ${
                  stat.label === 'Your Earnings'
                    ? 'bg-gradient-to-br from-emerald-50 to-teal-50 border-emerald-200'
                    : 'bg-white border-gray-200'
                }`}
              >
                <p className="text-xs text-gray-500 mb-1">{stat.label}</p>
                <p className="text-2xl font-bold text-gray-900">{stat.value}</p>
                {stat.hint && <p className="text-[11px] text-emerald-700/80 mt-1">{stat.hint}</p>}
              </div>
            ))}
          </div>
        )}

        {/* ── Product table ── */}
        {productsLoading && products.length === 0 ? (
          <div className="flex items-center justify-center py-20">
            <svg className="animate-spin h-6 w-6 text-emerald-500" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
          </div>
        ) : productsError ? (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
            Failed to load products. <button onClick={() => refetchProducts()} className="underline">Retry</button>
          </div>
        ) : products.length === 0 ? (
          <EmptyState />
        ) : (
          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <table className="w-full text-left" aria-label="Your products">
              <thead>
                <tr className="border-b border-gray-100 bg-gray-50/60">
                  {['Product', 'Status', 'Price', 'Sales', ''].map((h) => (
                    <th key={h} className="py-3 px-6 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {products.map((product) => (
                  <ProductRow
                    key={product.id}
                    product={product}
                    onUnpublish={unpublishProduct}
                    onDelete={deleteProduct}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </AppLayout>
  );
}
