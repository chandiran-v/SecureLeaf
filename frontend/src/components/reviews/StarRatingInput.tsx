import { useRef } from 'react';
import StarIcon from './StarIcon';

interface StarRatingInputProps {
  value: number; // 0 = nothing selected yet
  onChange: (rating: number) => void;
  disabled?: boolean;
}

/**
 * Keyboard-accessible star picker for the review form — an ARIA `radiogroup` of five
 * `radio` options (D6, frontend spec). Arrow keys move AND select (the standard radiogroup
 * pattern: only the checked option is normally tab-reachable), Space/Enter selects the
 * focused star, and clicking works too.
 */
export default function StarRatingInput({ value, onChange, disabled }: StarRatingInputProps) {
  const starRefs = useRef<Array<HTMLButtonElement | null>>([]);

  function select(star: number) {
    if (disabled) return;
    onChange(star);
    starRefs.current[star - 1]?.focus();
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLButtonElement>, star: number) {
    if (disabled) return;
    if (e.key === 'ArrowRight' || e.key === 'ArrowUp') {
      e.preventDefault();
      select(star < 5 ? star + 1 : 1);
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') {
      e.preventDefault();
      select(star > 1 ? star - 1 : 5);
    } else if (e.key === ' ' || e.key === 'Enter') {
      e.preventDefault();
      select(star);
    }
  }

  return (
    <div role="radiogroup" aria-label="Rating" className="flex items-center gap-1">
      {[1, 2, 3, 4, 5].map((star) => {
        const checked = star === value;
        // Roving tabindex: only the checked star (or the first, before anything is picked)
        // sits on the Tab order — arrow keys move focus within the group instead.
        const tabbable = value === 0 ? star === 1 : checked;
        return (
          <button
            key={star}
            ref={(el) => {
              starRefs.current[star - 1] = el;
            }}
            type="button"
            role="radio"
            aria-checked={checked}
            aria-label={`${star} star${star > 1 ? 's' : ''}`}
            tabIndex={tabbable ? 0 : -1}
            disabled={disabled}
            onClick={() => select(star)}
            onKeyDown={(e) => handleKeyDown(e, star)}
            className="p-0.5 rounded focus:outline-none focus:ring-2 focus:ring-emerald-500/40 disabled:cursor-not-allowed"
          >
            <StarIcon filled={star <= value} className="w-7 h-7" />
          </button>
        );
      })}
    </div>
  );
}
