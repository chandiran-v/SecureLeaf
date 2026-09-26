import AppLayout from '../../components/layout/AppLayout';
import SearchBar from '../../components/marketplace/SearchBar';
import ProductFilters from '../../components/marketplace/ProductFilters';
import ProductGrid from '../../components/marketplace/ProductGrid';
import Pagination from '../../components/marketplace/Pagination';
import { useProductSearch } from '../../hooks/useProductSearch';

/**
 * Public marketplace — no auth required (see App.tsx: this route sits outside
 * ProtectedRoute). All browsing state lives in the URL via useProductSearch (D6).
 */
export default function MarketplacePage() {
  const {
    searchQuery,
    category,
    sort,
    free,
    minRating,
    products,
    totalElements,
    pageNumber,
    totalPages,
    loading,
    error,
    refetch,
    setSearchQuery,
    setCategory,
    setSort,
    setFree,
    setMinRating,
    setPage,
  } = useProductSearch();

  return (
    <AppLayout>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <div className="mb-8">
          <h1 className="text-2xl font-bold text-gray-900">Marketplace</h1>
          <p className="text-gray-500 text-sm mt-1">
            {totalElements > 0 ? `${totalElements} product${totalElements === 1 ? '' : 's'}` : 'Browse products from our creators'}
          </p>
        </div>

        <div className="flex flex-col sm:flex-row gap-3 mb-6">
          <div className="flex-1">
            <SearchBar value={searchQuery} onChange={setSearchQuery} />
          </div>
          <ProductFilters
            category={category}
            onCategoryChange={setCategory}
            sort={sort}
            onSortChange={setSort}
            free={free}
            onFreeChange={setFree}
            minRating={minRating}
            onMinRatingChange={setMinRating}
          />
        </div>

        <ProductGrid products={products} loading={loading} error={error} onRetry={() => refetch()} />

        <Pagination pageNumber={pageNumber} totalPages={totalPages} onPageChange={setPage} />
      </div>
    </AppLayout>
  );
}
