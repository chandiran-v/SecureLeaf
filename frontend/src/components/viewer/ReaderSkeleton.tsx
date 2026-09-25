/** D10 — shown while startViewerSession is in flight, before there's a page to draw yet. */
export default function ReaderSkeleton() {
  return (
    <div className="fixed inset-0 flex flex-col items-center justify-center gap-4 bg-gray-950 text-gray-400" role="status">
      <div className="h-10 w-10 rounded-full border-2 border-gray-700 border-t-emerald-500 animate-spin" />
      <p className="text-sm">Opening your book…</p>
    </div>
  );
}
