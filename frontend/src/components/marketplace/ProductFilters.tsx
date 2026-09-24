import { useQuery } from '@apollo/client';
import { CATEGORIES } from '../../graphql/queries/marketplace.queries';
import type { Category, ProductSortBy } from '../../types';

interface ProductFiltersProps {
  category: string | undefined;
  onCategoryChange: (slug: string | undefined) => void;
  sort: ProductSortBy | undefined;
  onSortChange: (sort: ProductSortBy | undefined) => void;
  free: boolean;
  onFreeChange: (free: boolean) => void;
}

const SORT_OPTIONS: { value: ProductSortBy; label: string }[] = [
  { value: 'newest', label: 'Newest' },
  { value: 'popular', label: 'Most popular' },
  { value: 'rating', label: 'Top rated' },
  { value: 'price_asc', label: 'Price: low to high' },
  { value: 'price_desc', label: 'Price: high to low' },
];

export default function ProductFilters({
  category,
  onCategoryChange,
  sort,
  onSortChange,
  free,
  onFreeChange,
}: ProductFiltersProps) {
  const { data } = useQuery<{ categories: Category[] }>(CATEGORIES);

  return (
    <div className="flex flex-wrap items-center gap-3">
      <select
        aria-label="Filter by category"
        value={category ?? ''}
        onChange={(e) => onCategoryChange(e.target.value || undefined)}
        className="text-sm rounded-lg border border-gray-200 bg-white px-3 py-2 focus:outline-none focus:ring-2 focus:ring-emerald-500/30"
      >
        <option value="">All categories</option>
        {data?.categories.map((c) => (
          <option key={c.id} value={c.slug}>
            {c.name}
          </option>
        ))}
      </select>

      <select
        aria-label="Sort by"
        value={sort ?? 'newest'}
        onChange={(e) => onSortChange(e.target.value as ProductSortBy)}
        className="text-sm rounded-lg border border-gray-200 bg-white px-3 py-2 focus:outline-none focus:ring-2 focus:ring-emerald-500/30"
      >
        {SORT_OPTIONS.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>

      <label className="flex items-center gap-2 text-sm text-gray-600 px-1 cursor-pointer">
        <input
          type="checkbox"
          checked={free}
          onChange={(e) => onFreeChange(e.target.checked)}
          className="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500/30"
        />
        Free only
      </label>
    </div>
  );
}
