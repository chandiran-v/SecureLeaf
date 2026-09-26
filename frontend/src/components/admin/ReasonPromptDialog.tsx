import { useState } from 'react';

/**
 * D4/D5 — suspend and take-down both require an admin to type a reason before the action goes
 * through (acceptance criterion 9). Same visual chrome as {@code ConfirmDialog} (overlay,
 * `role="alertdialog"`, Cancel/Confirm layout), plus a required textarea: Confirm stays disabled
 * until the trimmed reason is non-empty, so an admin can't submit a blank one by rushing through
 * the dialog.
 */
export default function ReasonPromptDialog({
  title,
  message,
  confirmLabel,
  onConfirm,
  onCancel,
  submitting,
}: {
  title: string;
  message: string;
  confirmLabel: string;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
  submitting?: boolean;
}) {
  const [reason, setReason] = useState('');
  const trimmedReason = reason.trim();

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4" role="presentation">
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="reason-dialog-title"
        className="w-full max-w-sm bg-white rounded-xl shadow-xl border border-gray-200 p-6"
      >
        <h2 id="reason-dialog-title" className="text-base font-semibold text-gray-900 mb-2">
          {title}
        </h2>
        <p className="text-sm text-gray-600 mb-3">{message}</p>
        <textarea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={3}
          placeholder="Reason (required)"
          aria-label="Reason"
          className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-900 placeholder-gray-400
                     focus:outline-none focus:ring-2 focus:ring-red-500/30"
        />
        <div className="flex justify-end gap-2 mt-4">
          <button
            type="button"
            onClick={onCancel}
            className="px-3 py-2 text-sm font-medium rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={!trimmedReason || submitting}
            onClick={() => onConfirm(trimmedReason)}
            className="px-3 py-2 text-sm font-semibold rounded-lg text-white bg-red-600 hover:bg-red-700
                       disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {submitting ? 'Saving…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
