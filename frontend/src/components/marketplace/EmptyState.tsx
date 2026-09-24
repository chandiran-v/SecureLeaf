export default function EmptyState() {
  return (
    <div className="text-center py-20" role="status">
      <div className="w-16 h-16 rounded-2xl bg-gray-50 flex items-center justify-center mx-auto mb-4">
        <svg className="w-8 h-8 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
            d="M21 21l-4.35-4.35M11 19a8 8 0 100-16 8 8 0 000 16z" />
        </svg>
      </div>
      <h3 className="text-lg font-semibold text-gray-900 mb-1">No products found</h3>
      <p className="text-gray-500 text-sm">Try a different search term or clear your filters.</p>
    </div>
  );
}
