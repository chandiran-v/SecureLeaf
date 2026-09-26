import { useEffect, useState } from 'react';
import type { ApolloError } from '@apollo/client';
import StarRating from './StarRating';
import StarRatingInput from './StarRatingInput';
import ConfirmDialog from '../ui/ConfirmDialog';
import type { Review } from '../../types';

const MAX_REVIEW_TEXT_LENGTH = 2000;

interface ReviewFormProps {
  myReview: Review | null;
  onSubmit: (rating: number, text: string) => Promise<void>;
  onDelete: () => Promise<void>;
  submitLoading: boolean;
  deleteLoading: boolean;
  submitError?: ApolloError;
}

/**
 * The entitled buyer's review form (D6, frontend spec) — prefilled from `myReview`, upsert on
 * submit (server-side), with Edit/Delete once a review exists. `editing` starts true when
 * there's no review yet, and false (a read-only summary) once one exists, so a buyer doesn't
 * see an empty form flash before their own review loads in.
 */
export default function ReviewForm({ myReview, onSubmit, onDelete, submitLoading, deleteLoading, submitError }: ReviewFormProps) {
  const [rating, setRating] = useState(myReview?.rating ?? 0);
  const [text, setText] = useState(myReview?.reviewText ?? '');
  const [editing, setEditing] = useState(!myReview);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  useEffect(() => {
    setRating(myReview?.rating ?? 0);
    setText(myReview?.reviewText ?? '');
    setEditing(!myReview);
  }, [myReview]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (rating === 0) return;
    await onSubmit(rating, text.trim());
    setEditing(false);
  }

  async function handleConfirmDelete() {
    await onDelete();
    setConfirmingDelete(false);
  }

  if (!editing && myReview) {
    return (
      <div>
        <div className="flex items-center gap-2">
          <StarRating rating={myReview.rating} size="w-4 h-4" />
          <span className="text-sm text-gray-500">Your review</span>
        </div>
        {myReview.reviewText && <p className="mt-1.5 text-sm text-gray-600 whitespace-pre-line">{myReview.reviewText}</p>}
        <div className="mt-2 flex gap-4">
          <button type="button" onClick={() => setEditing(true)} className="text-sm font-medium text-emerald-600 hover:text-emerald-700">
            Edit
          </button>
          <button type="button" onClick={() => setConfirmingDelete(true)} className="text-sm font-medium text-red-600 hover:text-red-700">
            Delete
          </button>
        </div>
        {confirmingDelete && (
          <ConfirmDialog
            title="Delete your review?"
            message="This removes your rating and review text. You can leave a new one any time."
            confirmLabel={deleteLoading ? 'Deleting…' : 'Delete'}
            danger
            onConfirm={handleConfirmDelete}
            onCancel={() => setConfirmingDelete(false)}
          />
        )}
      </div>
    );
  }

  const errorMessage = submitError?.graphQLErrors?.[0]?.message ?? (submitError ? 'Could not submit your review.' : null);

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <StarRatingInput value={rating} onChange={setRating} disabled={submitLoading} />
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        maxLength={MAX_REVIEW_TEXT_LENGTH}
        rows={3}
        placeholder="Share your thoughts (optional)"
        aria-label="Review text"
        className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-900 placeholder-gray-400
                   focus:outline-none focus:ring-2 focus:ring-emerald-500/30"
      />
      {errorMessage && (
        <p className="text-sm text-red-600" role="alert">
          {errorMessage}
        </p>
      )}
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={rating === 0 || submitLoading}
          className="px-4 py-2 rounded-lg bg-emerald-600 text-white text-sm font-semibold shadow-sm
                     hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {submitLoading ? 'Saving…' : myReview ? 'Save changes' : 'Submit review'}
        </button>
        {myReview && (
          <button
            type="button"
            onClick={() => setEditing(false)}
            className="px-4 py-2 rounded-lg border border-gray-200 text-sm text-gray-600 hover:bg-gray-50"
          >
            Cancel
          </button>
        )}
      </div>
    </form>
  );
}
