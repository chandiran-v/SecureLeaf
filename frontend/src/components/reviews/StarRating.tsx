import StarIcon from './StarIcon';

/** Read-only star display — rounds to the nearest whole star (good enough for an average like 3.7). */
export default function StarRating({ rating, size = 'w-5 h-5' }: { rating: number; size?: string }) {
  const rounded = Math.round(rating);
  return (
    <div className="flex items-center gap-0.5" role="img" aria-label={`${rating.toFixed(1)} out of 5 stars`}>
      {[1, 2, 3, 4, 5].map((star) => (
        <StarIcon key={star} filled={star <= rounded} className={size} />
      ))}
    </div>
  );
}
