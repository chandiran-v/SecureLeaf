import { useState } from 'react';
import { Link } from 'react-router-dom';
import AppLayout from '../../components/layout/AppLayout';
import AdminNav from '../../components/admin/AdminNav';
import Pagination from '../../components/marketplace/Pagination';
import StatusBadge from '../../components/ui/StatusBadge';
import ReasonPromptDialog from '../../components/admin/ReasonPromptDialog';
import { formatPrice } from '../../lib/formatPrice';
import { useAdminProducts } from '../../hooks/useAdminProducts';
import type { AdminProduct, ProductStatus } from '../../types';

const STATUS_OPTIONS: ProductStatus[] = ['DRAFT', 'PROCESSING', 'LIVE', 'UNPUBLISHED', 'FAILED'];

function ProductRow({
  product,
  onTakeDown,
  onRestore,
}: {
  product: AdminProduct;
  onTakeDown: (id: string) => void;
  onRestore: (id: string) => Promise<void>;
}) {
  return (
    <tr className="hover:bg-gray-50 transition-colors">
      <td className="py-4 px-6">
        <Link to={`/product/${product.id}`} className="text-sm font-medium text-gray-900 hover:text-emerald-700">
          {product.title}
        </Link>
        <p className="text-xs text-gray-400">by {product.creator.displayName}</p>
      </td>
      <td className="py-4 px-6">
        <StatusBadge status={product.status} />
        {product.status === 'UNPUBLISHED' && product.takedownReason && (
          <p className="text-[11px] text-red-600 mt-1 max-w-[220px] line-clamp-2" title={product.takedownReason}>
            {product.takedownReason}
          </p>
        )}
      </td>
      <td className="py-4 px-6 text-sm text-gray-700 font-medium">{formatPrice(product.pricePaise)}</td>
      <td className="py-4 px-6 text-sm text-gray-500">{product.totalSales} sale{product.totalSales !== 1 ? 's' : ''}</td>
      <td className="py-4 px-6">
        <div className="flex items-center gap-2 justify-end">
          {product.status === 'LIVE' && (
            <button
              onClick={() => onTakeDown(product.id)}
              className="text-xs px-3 py-1.5 rounded-lg border border-red-100 text-red-500 hover:bg-red-50 transition-colors"
            >
              Take down
            </button>
          )}
          {product.takedownReason && (
            <button
              onClick={() => void onRestore(product.id)}
              className="text-xs px-3 py-1.5 rounded-lg border border-emerald-200 text-emerald-700 hover:bg-emerald-50 transition-colors"
            >
              Restore
            </button>
          )}
        </div>
      </td>
    </tr>
  );
}

/** D5 — filterable product table. Take-down requires a reason (ReasonPromptDialog); restore is
 *  a single click, only offered on a product an admin actually took down. */
export default function AdminProductsPage() {
  const {
    products,
    totalPages,
    pageNumber,
    loading,
    error,
    search,
    status,
    takeDownProduct,
    takeDownLoading,
    restoreProduct,
    setSearch,
    setStatus,
    setPage,
  } = useAdminProducts();

  const [takingDownProductId, setTakingDownProductId] = useState<string | null>(null);

  async function handleConfirmTakeDown(reason: string) {
    if (!takingDownProductId) return;
    await takeDownProduct(takingDownProductId, reason);
    setTakingDownProductId(null);
  }

  return (
    <AppLayout>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <div className="mb-2">
          <h1 className="text-2xl font-bold text-gray-900">Products</h1>
          <p className="text-gray-500 text-sm mt-1">Moderate marketplace listings.</p>
        </div>
        <AdminNav />

        {/* ── Filters ── */}
        <div className="flex flex-wrap items-center gap-3 mb-6">
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search title…"
            aria-label="Search products"
            className="flex-1 min-w-[220px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-900 placeholder-gray-400
                       focus:outline-none focus:ring-2 focus:ring-emerald-500/30"
          />
          <select
            value={status ?? ''}
            onChange={(e) => setStatus((e.target.value || undefined) as ProductStatus | undefined)}
            aria-label="Filter by status"
            className="rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700"
          >
            <option value="">All statuses</option>
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>

        {loading && products.length === 0 ? (
          <div className="flex items-center justify-center py-20">
            <svg className="animate-spin h-6 w-6 text-emerald-500" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
          </div>
        ) : error ? (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
            Failed to load products.
          </div>
        ) : products.length === 0 ? (
          <p className="text-center py-20 text-gray-500 text-sm">No products match these filters.</p>
        ) : (
          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <table className="w-full text-left" aria-label="Products">
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
                    onTakeDown={setTakingDownProductId}
                    onRestore={restoreProduct}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}

        <Pagination pageNumber={pageNumber} totalPages={totalPages} onPageChange={setPage} />

        {takingDownProductId && (
          <ReasonPromptDialog
            title="Take down this product?"
            message="It disappears from the marketplace immediately. Buyers who already own it keep their access, and the creator cannot republish it without your restore."
            confirmLabel="Take down"
            submitting={takeDownLoading}
            onConfirm={(reason) => void handleConfirmTakeDown(reason)}
            onCancel={() => setTakingDownProductId(null)}
          />
        )}
      </div>
    </AppLayout>
  );
}
