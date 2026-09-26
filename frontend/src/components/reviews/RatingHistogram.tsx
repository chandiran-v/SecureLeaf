import StarRating from './StarRating';
import type { RatingCount } from '../../types';

interface RatingHistogramProps {
  averageRating: number | null;
  reviewCount: number;
  breakdown: RatingCount[]; // always all five stars, D6
}

export default function RatingHistogram({ averageRating, reviewCount, breakdown }: RatingHistogramProps) {
  if (reviewCount === 0 || averageRating == null) {
    return <p className="text-sm text-gray-400">No reviews yet</p>;
  }

  const maxCount = Math.max(1, ...breakdown.map((b) => b.count));

  return (
    <div className="flex flex-col sm:flex-row gap-6">
      <div className="flex flex-col items-start gap-1">
        <span className="text-3xl font-bold text-gray-900">{averageRating.toFixed(1)}</span>
        <StarRating rating={averageRating} />
        <span className="text-sm text-gray-500">
          {reviewCount} review{reviewCount === 1 ? '' : 's'}
        </span>
      </div>

      <div className="flex-1 min-w-[180px] space-y-1">
        {breakdown.map((row) => (
          <div key={row.rating} className="flex items-center gap-2 text-xs text-gray-500">
            <span className="w-3 text-right">{row.rating}</span>
            <div className="flex-1 h-2 rounded-full bg-gray-100 overflow-hidden">
              <div
                className="h-full bg-amber-400 rounded-full"
                style={{ width: `${(row.count / maxCount) * 100}%` }}
              />
            </div>
            <span className="w-6 text-right">{row.count}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
