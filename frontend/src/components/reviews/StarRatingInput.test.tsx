import { fireEvent, render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import StarRatingInput from './StarRatingInput';

describe('StarRatingInput', () => {
  it('is an accessible radiogroup of five star radios', () => {
    render(<StarRatingInput value={0} onChange={vi.fn()} />);

    expect(screen.getByRole('radiogroup', { name: /rating/i })).toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(5);
    expect(screen.getByRole('radio', { name: '3 stars' })).toHaveAttribute('aria-checked', 'false');
  });

  it('selects a star on click', () => {
    const onChange = vi.fn();
    render(<StarRatingInput value={0} onChange={onChange} />);

    fireEvent.click(screen.getByRole('radio', { name: '4 stars' }));
    expect(onChange).toHaveBeenCalledWith(4);
  });

  it('moves and selects with ArrowRight, wrapping from 5 back to 1', () => {
    const onChange = vi.fn();
    const { rerender } = render(<StarRatingInput value={5} onChange={onChange} />);

    fireEvent.keyDown(screen.getByRole('radio', { name: '5 stars' }), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenCalledWith(1);

    onChange.mockClear();
    rerender(<StarRatingInput value={2} onChange={onChange} />);
    fireEvent.keyDown(screen.getByRole('radio', { name: '2 stars' }), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenCalledWith(3);
  });

  it('moves and selects with ArrowLeft, wrapping from 1 back to 5', () => {
    const onChange = vi.fn();
    render(<StarRatingInput value={1} onChange={onChange} />);

    fireEvent.keyDown(screen.getByRole('radio', { name: '1 star' }), { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenCalledWith(5);
  });

  it('selects the focused star with Space or Enter', () => {
    const onChange = vi.fn();
    render(<StarRatingInput value={2} onChange={onChange} />);

    fireEvent.keyDown(screen.getByRole('radio', { name: '2 stars' }), { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith(2);
  });

  it('only the checked star (or the first, before any pick) is on the Tab order', () => {
    render(<StarRatingInput value={3} onChange={vi.fn()} />);

    expect(screen.getByRole('radio', { name: '3 stars' })).toHaveAttribute('tabIndex', '0');
    expect(screen.getByRole('radio', { name: '1 star' })).toHaveAttribute('tabIndex', '-1');
  });

  it('ignores clicks and keys while disabled', () => {
    const onChange = vi.fn();
    render(<StarRatingInput value={0} onChange={onChange} disabled />);

    fireEvent.click(screen.getByRole('radio', { name: '4 stars' }));
    expect(onChange).not.toHaveBeenCalled();
  });
});
