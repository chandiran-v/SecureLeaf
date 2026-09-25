/**
 * D4 — one panel, two copies. SUPERSEDED means a different device just took over (VIEW-10);
 * EXPIRED means this device's own lease lapsed and the one silent auto-restart (D4) also failed.
 * Both recover the same way: start a fresh session and take the slot back.
 */
export default function SessionEndedPanel({
  variant,
  onContinue,
}: {
  variant: 'superseded' | 'expired';
  onContinue: () => void;
}) {
  const isSuperseded = variant === 'superseded';
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-gray-950/95 px-6 text-center" role="alert">
      <div className="max-w-sm">
        <h2 className="mb-2 text-lg font-semibold text-white">
          {isSuperseded ? 'This book was opened on another device or tab' : 'Your reading session ended'}
        </h2>
        <p className="mb-6 text-sm text-gray-400">
          {isSuperseded
            ? 'Only one device can read this book at a time.'
            : 'Your session timed out from inactivity. You can pick up right where you left off.'}
        </p>
        <button
          onClick={onContinue}
          className="rounded-lg bg-gradient-to-r from-emerald-600 to-teal-600 px-5 py-2.5 text-sm font-semibold text-white transition-all hover:from-emerald-700 hover:to-teal-700"
        >
          {isSuperseded ? 'Read here instead' : 'Continue reading'}
        </button>
      </div>
    </div>
  );
}
