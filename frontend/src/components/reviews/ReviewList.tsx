import StarRating from './StarRating';
import Pagination from '../marketplace/Pagination';
import type { Review } from '../../types';

interface ReviewListProps {
  reviews: Review[];
  totalElements: number;
  pageNumber: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}

export default function ReviewList({ reviews, totalElements, pageNumber, totalPages, onPageChange }: ReviewListProps) {
  if (totalElements === 0) {
    return <p className="text-sm text-gray-400">No reviews yet — be the first to leave one.</p>;
  }

  return (
    <div>
      <ul className="space-y-5">
        {reviews.map((review) => (
          <li key={review.id} className="border-b border-gray-100 pb-4 last:border-0">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <StarRating rating={review.rating} size="w-4 h-4" />
                <span className="text-sm font-medium text-gray-900">{review.reviewer.displayName}</span>
              </div>
              <time className="text-xs text-gray-400" dateTime={review.createdAt}>
                {new Date(review.createdAt).toLocaleDateString()}
              </time>
            </div>
            {review.reviewText && <p className="mt-1.5 text-sm text-gray-600 whitespace-pre-line">{review.reviewText}</p>}
          </li>
        ))}
      </ul>
      <Pagination pageNumber={pageNumber} totalPages={totalPages} onPageChange={onPageChange} />
    </div>
  );
}
