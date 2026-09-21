import type { ProductStatus } from '../../types';

const STATUS_STYLES: Record<ProductStatus, { bg: string; text: string; dot: string; label: string }> = {
  LIVE:        { bg: 'bg-emerald-50',  text: 'text-emerald-700', dot: 'bg-emerald-500', label: 'Live' },
  PROCESSING:  { bg: 'bg-amber-50',   text: 'text-amber-700',   dot: 'bg-amber-500',   label: 'Processing' },
  DRAFT:       { bg: 'bg-gray-100',   text: 'text-gray-600',    dot: 'bg-gray-400',    label: 'Draft' },
  UNPUBLISHED: { bg: 'bg-slate-100',  text: 'text-slate-600',   dot: 'bg-slate-400',   label: 'Unpublished' },
  FAILED:      { bg: 'bg-red-50',     text: 'text-red-700',     dot: 'bg-red-500',     label: 'Failed' },
};

export default function StatusBadge({ status }: { status: ProductStatus }) {
  const s = STATUS_STYLES[status];
  return (
    <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium ${s.bg} ${s.text}`}
          data-testid={`status-badge-${status.toLowerCase()}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${s.dot} ${status === 'PROCESSING' ? 'animate-pulse' : ''}`} />
      {s.label}
    </span>
  );
}
