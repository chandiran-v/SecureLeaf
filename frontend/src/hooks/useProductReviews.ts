import { useMutation, useQuery } from '@apollo/client';
import { useCallback, useState } from 'react';
import { PRODUCT_REVIEWS, MY_REVIEW, RATING_BREAKDOWN } from '../graphql/queries/review.queries';
import { SUBMIT_REVIEW, DELETE_MY_REVIEW } from '../graphql/mutations/review.mutations';
import { useAuthStore } from '../store/authStore';
import type { RatingCount, Review, ReviewPage } from '../types';

const PAGE_SIZE = 10;

/**
 * Bundles everything the product detail page's review section needs: the paginated public
 * review list, the rating histogram, the current buyer's own review (if any), and the
 * submit/delete mutations. `ownedByMe` gates myReview/submit/delete — a non-entitled visitor
 * has nothing to prefill and nothing they're allowed to write (the server enforces this too;
 * this only avoids firing a query/mutation that would just come back as an error).
 */
export function useProductReviews(productId: string, ownedByMe: boolean) {
  const { isAuthenticated } = useAuthStore();
  const [page, setPage] = useState(0);

  const { data: reviewsData, loading: reviewsLoading, error: reviewsError, refetch: refetchReviews } = useQuery<{
    productReviews: ReviewPage;
  }>(PRODUCT_REVIEWS, {
    variables: { productId, page, size: PAGE_SIZE },
    skip: !productId,
    fetchPolicy: 'cache-and-network',
  });

  const { data: breakdownData, refetch: refetchBreakdown } = useQuery<{ ratingBreakdown: RatingCount[] }>(
    RATING_BREAKDOWN,
    { variables: { productId }, skip: !productId }
  );

  const { data: myReviewData, refetch: refetchMyReview } = useQuery<{ myReview: Review | null }>(MY_REVIEW, {
    variables: { productId },
    skip: !productId || !isAuthenticated || !ownedByMe,
  });

  const [submitReviewMutation, { loading: submitLoading, error: submitError }] =
    useMutation<{ submitReview: Review }>(SUBMIT_REVIEW);

  const [deleteMyReviewMutation, { loading: deleteLoading }] =
    useMutation<{ deleteMyReview: boolean }>(DELETE_MY_REVIEW);

  const refetchAll = useCallback(() => {
    refetchReviews();
    refetchBreakdown();
    refetchMyReview();
  }, [refetchReviews, refetchBreakdown, refetchMyReview]);

  const submitReview = useCallback(
    async (rating: number, reviewText: string) => {
      await submitReviewMutation({ variables: { input: { productId, rating, reviewText: reviewText || undefined } } });
      refetchAll();
    },
    [submitReviewMutation, productId, refetchAll]
  );

  const deleteMyReview = useCallback(async () => {
    await deleteMyReviewMutation({ variables: { productId } });
    refetchAll();
  }, [deleteMyReviewMutation, productId, refetchAll]);

  return {
    reviews: reviewsData?.productReviews.content ?? [],
    totalElements: reviewsData?.productReviews.totalElements ?? 0,
    totalPages: reviewsData?.productReviews.totalPages ?? 0,
    pageNumber: reviewsData?.productReviews.pageNumber ?? 0,
    reviewsLoading,
    reviewsError,
    setPage,
    breakdown: breakdownData?.ratingBreakdown ?? [],
    myReview: myReviewData?.myReview ?? null,
    submitReview,
    submitLoading,
    submitError,
    deleteMyReview,
    deleteLoading,
  };
}
