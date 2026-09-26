import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ReviewForm from './ReviewForm';
import type { Review } from '../../types';

const existingReview: Review = {
  id: '1',
  reviewer: { displayName: 'Bala Buyer' },
  rating: 4,
  reviewText: 'Pretty good.',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
};

describe('ReviewForm', () => {
  it('shows an empty form (no rating picked) when there is no existing review', () => {
    render(<ReviewForm myReview={null} onSubmit={vi.fn()} onDelete={vi.fn()} submitLoading={false} deleteLoading={false} />);

    expect(screen.getByRole('radiogroup', { name: /rating/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /submit review/i })).toBeDisabled();
  });

  it('prefills a read-only summary from myReview, with Edit and Delete', () => {
    render(
      <ReviewForm myReview={existingReview} onSubmit={vi.fn()} onDelete={vi.fn()} submitLoading={false} deleteLoading={false} />
    );

    expect(screen.getByText('Pretty good.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /edit/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /delete/i })).toBeInTheDocument();
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument();
  });

  it('clicking Edit reopens the form prefilled with the existing rating and text', () => {
    render(
      <ReviewForm myReview={existingReview} onSubmit={vi.fn()} onDelete={vi.fn()} submitLoading={false} deleteLoading={false} />
    );

    fireEvent.click(screen.getByRole('button', { name: /edit/i }));

    expect(screen.getByRole('radio', { name: '4 stars' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByLabelText(/review text/i)).toHaveValue('Pretty good.');
    expect(screen.getByRole('button', { name: /save changes/i })).toBeEnabled();
  });

  it('submits the edited rating and text', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(
      <ReviewForm myReview={existingReview} onSubmit={onSubmit} onDelete={vi.fn()} submitLoading={false} deleteLoading={false} />
    );

    fireEvent.click(screen.getByRole('button', { name: /edit/i }));
    fireEvent.click(screen.getByRole('radio', { name: '5 stars' }));
    fireEvent.change(screen.getByLabelText(/review text/i), { target: { value: 'Actually excellent.' } });
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(5, 'Actually excellent.'));
  });

  it('asks for confirmation before deleting, and calls onDelete only after confirming', async () => {
    const onDelete = vi.fn().mockResolvedValue(undefined);
    render(
      <ReviewForm myReview={existingReview} onSubmit={vi.fn()} onDelete={onDelete} submitLoading={false} deleteLoading={false} />
    );

    fireEvent.click(screen.getByRole('button', { name: /delete/i }));
    expect(onDelete).not.toHaveBeenCalled();

    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(onDelete).toHaveBeenCalled());
  });
});
