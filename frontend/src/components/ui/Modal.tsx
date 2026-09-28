import { useEffect, useId, useRef, type ReactNode } from 'react';

interface ModalProps {
  open: boolean;
  title: string;
  /** Main text. */
  children: ReactNode;
  /** Buttons, right-aligned on wide screens and stacked on phones. */
  actions: ReactNode;
  /** Optional icon shown above the title. */
  icon?: ReactNode;
  /**
   * When given, Escape and a click on the backdrop call it. Leave it out for a modal the user
   * must answer through one of the actions (e.g. "your session expired").
   */
  onClose?: () => void;
}

/**
 * Reusable accessible dialog: `role="dialog"` + `aria-modal`, labelled by its title and
 * described by its body, focus moved into it on open (to the first button) and restored to
 * whatever had focus before when it closes.
 */
export default function Modal({ open, title, children, actions, icon, onClose }: ModalProps) {
  const titleId = useId();
  const bodyId = useId();
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const previouslyFocused = document.activeElement as HTMLElement | null;
    panelRef.current?.querySelector<HTMLElement>('button, [href], [tabindex]:not([tabindex="-1"])')?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && onClose) onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      previouslyFocused?.focus?.();
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-gray-900/50 backdrop-blur-sm" aria-hidden="true" onClick={onClose} />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        className="relative w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl"
      >
        {icon && <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-amber-50">{icon}</div>}
        <h2 id={titleId} className="text-lg font-semibold text-gray-900">
          {title}
        </h2>
        <div id={bodyId} className="mt-2 text-sm leading-relaxed text-gray-600">
          {children}
        </div>
        <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">{actions}</div>
      </div>
    </div>
  );
}
