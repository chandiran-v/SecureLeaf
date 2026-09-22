export default function ErrorState({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700" role="alert">
      Failed to load products.{' '}
      <button onClick={onRetry} className="underline font-medium">
        Retry
      </button>
    </div>
  );
}
