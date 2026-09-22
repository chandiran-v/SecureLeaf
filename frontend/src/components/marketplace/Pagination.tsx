interface PaginationProps {
  pageNumber: number; // 0-indexed
  totalPages: number;
  onPageChange: (page: number) => void;
}

export default function Pagination({ pageNumber, totalPages, onPageChange }: PaginationProps) {
  if (totalPages <= 1) return null;

  return (
    <nav className="flex items-center justify-center gap-2 mt-8" aria-label="Pagination">
      <button
        onClick={() => onPageChange(pageNumber - 1)}
        disabled={pageNumber === 0}
        className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-600 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-50"
      >
        Previous
      </button>
      <span className="text-sm text-gray-500 px-2">
        Page {pageNumber + 1} of {totalPages}
      </span>
      <button
        onClick={() => onPageChange(pageNumber + 1)}
        disabled={pageNumber >= totalPages - 1}
        className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 text-gray-600 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-50"
      >
        Next
      </button>
    </nav>
  );
}
