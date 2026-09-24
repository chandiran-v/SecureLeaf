import ProductCard from './ProductCard';
import ProductCardSkeleton from './ProductCardSkeleton';
import EmptyState from './EmptyState';
import ErrorState from './ErrorState';
import type { Product } from '../../types';
import type { ApolloError } from '@apollo/client';

interface ProductGridProps {
  products: Product[];
  loading: boolean;
  error?: ApolloError;
  onRetry: () => void;
}

export default function ProductGrid({ products, loading, error, onRetry }: ProductGridProps) {
  if (error) return <ErrorState onRetry={onRetry} />;

  if (loading && products.length === 0) {
    return (
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-5">
        {Array.from({ length: 8 }).map((_, i) => (
          <ProductCardSkeleton key={i} />
        ))}
      </div>
    );
  }

  if (products.length === 0) return <EmptyState />;

  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-5">
      {products.map((product) => (
        <ProductCard key={product.id} product={product} />
      ))}
    </div>
  );
}
