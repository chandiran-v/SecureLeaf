import { Link } from 'react-router-dom';

/**
 * D4 — covers both a 403/NOT_ENTITLED from starting the session (never had access) and a 403
 * discovered on a later tile fetch (e.g. a refund revoked access mid-session, D6 step 6 on the
 * backend). Either way the buyer needs the same next step: back to the product, not the reader.
 */
export default function NotEntitledPanel({ productId }: { productId: string }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-gray-950 px-6 text-center" role="alert">
      <div className="max-w-sm">
        <h2 className="mb-2 text-lg font-semibold text-white">You don&apos;t have access to this</h2>
        <p className="mb-6 text-sm text-gray-400">
          You need to own this product to read it here. If you just bought it, try refreshing in a moment.
        </p>
        <Link
          to={`/product/${productId}`}
          className="inline-flex rounded-lg bg-white/10 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-white/20"
        >
          Back to product page
        </Link>
      </div>
    </div>
  );
}
