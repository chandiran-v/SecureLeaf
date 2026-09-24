import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@apollo/client';
import AppLayout from '../../components/layout/AppLayout';
import PreviewPane from '../../components/marketplace/PreviewPane';
import BuyPanel from '../../components/marketplace/BuyPanel';
import { PRODUCT_DETAIL } from '../../graphql/queries/marketplace.queries';
import { formatPrice } from '../../lib/formatPrice';
import type { Product } from '../../types';

export default function ProductDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { data, loading, error } = useQuery<{ product: Product | null }>(PRODUCT_DETAIL, {
    variables: { id },
    skip: !id,
  });

  if (loading) {
    return (
      <AppLayout>
        <div className="max-w-5xl mx-auto px-4 py-20 text-center text-gray-400">Loading…</div>
      </AppLayout>
    );
  }

  if (error || !data?.product) {
    return (
      <AppLayout>
        <div className="max-w-5xl mx-auto px-4 py-20 text-center">
          <h1 className="text-xl font-semibold text-gray-900 mb-2">Product not found</h1>
          <Link to="/marketplace" className="text-emerald-600 text-sm underline">
            Back to marketplace
          </Link>
        </div>
      </AppLayout>
    );
  }

  const product = data.product;

  return (
    <AppLayout>
      <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <div className="grid md:grid-cols-2 gap-10">
          <div>
            <PreviewPane productId={product.id} freePreviewPages={product.freePreviewPages} />
          </div>

          <div>
            <p className="text-xs font-medium text-emerald-600 mb-2">{product.category.name}</p>
            <h1 className="text-2xl font-bold text-gray-900 mb-2">{product.title}</h1>
            <p className="text-sm text-gray-400 mb-4">by {product.creator.displayName}</p>

            <div className="flex items-center gap-3 mb-6">
              <span className="text-2xl font-bold text-gray-900">{formatPrice(product.pricePaise)}</span>
              {product.averageRating != null && (
                <span className="flex items-center gap-1 text-sm text-gray-500">
                  <svg className="w-4 h-4 text-amber-400" fill="currentColor" viewBox="0 0 20 20">
                    <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.286 3.957a1 1 0 00.95.69h4.162c.969 0 1.371 1.24.588 1.81l-3.367 2.447a1 1 0 00-.364 1.118l1.287 3.957c.3.922-.755 1.688-1.539 1.118l-3.367-2.447a1 1 0 00-1.175 0l-3.367 2.447c-.784.57-1.838-.196-1.539-1.118l1.287-3.957a1 1 0 00-.364-1.118L2.063 9.384c-.783-.57-.38-1.81.588-1.81h4.163a1 1 0 00.95-.69l1.285-3.957z" />
                  </svg>
                  {product.averageRating.toFixed(1)} · {product.totalSales} sold
                </span>
              )}
            </div>

            <p className="text-sm text-gray-600 leading-relaxed whitespace-pre-line mb-6">{product.description}</p>

            {product.tags.length > 0 && (
              <div className="flex flex-wrap gap-2 mb-8">
                {product.tags.map((tag) => (
                  <span key={tag} className="text-xs px-2.5 py-1 rounded-full bg-gray-100 text-gray-600">
                    {tag}
                  </span>
                ))}
              </div>
            )}

            <BuyPanel product={product} />
          </div>
        </div>
      </div>
    </AppLayout>
  );
}
