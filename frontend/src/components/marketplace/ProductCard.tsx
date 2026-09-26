import { Link } from 'react-router-dom';
import { formatPrice } from '../../lib/formatPrice';
import type { Product } from '../../types';

/**
 * NOTE on `key`: never use `product.thumbnailUrl` as a React list key. It's a
 * presigned URL that differs on every request (fresh signature each time,
 * see backend ProductFieldResolver), so using it as a key would remount the
 * card's <img> on every refetch. Use `product.id` instead (done by the caller).
 */
export default function ProductCard({ product }: { product: Product }) {
  return (
    <Link
      to={`/product/${product.id}`}
      className="group block bg-white rounded-xl border border-gray-200 overflow-hidden hover:shadow-md hover:border-emerald-200 transition-all"
    >
      <div className="aspect-[3/4] bg-gray-100 overflow-hidden">
        {product.thumbnailUrl ? (
          <img
            src={product.thumbnailUrl}
            alt={product.title}
            className="w-full h-full object-cover group-hover:scale-[1.03] transition-transform duration-300"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center">
            <svg className="w-10 h-10 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
            </svg>
          </div>
        )}
      </div>
      <div className="p-4">
        <p className="text-xs text-emerald-600 font-medium mb-1">{product.category.name}</p>
        <h3 className="text-sm font-semibold text-gray-900 line-clamp-2 mb-1">{product.title}</h3>
        <p className="text-xs text-gray-400 mb-2">by {product.creator.displayName}</p>
        <div className="flex items-center justify-between">
          <span className="text-sm font-bold text-gray-900">{formatPrice(product.pricePaise)}</span>
          {product.averageRating != null ? (
            <span className="flex items-center gap-1 text-xs text-gray-500">
              <svg className="w-3.5 h-3.5 text-amber-400" fill="currentColor" viewBox="0 0 20 20">
                <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.286 3.957a1 1 0 00.95.69h4.162c.969 0 1.371 1.24.588 1.81l-3.367 2.447a1 1 0 00-.364 1.118l1.287 3.957c.3.922-.755 1.688-1.539 1.118l-3.367-2.447a1 1 0 00-1.175 0l-3.367 2.447c-.784.57-1.838-.196-1.539-1.118l1.287-3.957a1 1 0 00-.364-1.118L2.063 9.384c-.783-.57-.38-1.81.588-1.81h4.163a1 1 0 00.95-.69l1.285-3.957z" />
              </svg>
              {product.averageRating.toFixed(1)}
            </span>
          ) : (
            <span className="text-xs text-gray-400">No reviews yet</span>
          )}
        </div>
      </div>
    </Link>
  );
}
