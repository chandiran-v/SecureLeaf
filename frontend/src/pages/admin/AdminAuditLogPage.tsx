import AppLayout from '../../components/layout/AppLayout';
import AdminNav from '../../components/admin/AdminNav';
import Pagination from '../../components/marketplace/Pagination';
import { useAdminActions } from '../../hooks/useAdminActions';

const ACTION_LABELS: Record<string, string> = {
  SUSPEND_USER: 'Suspended user',
  REACTIVATE_USER: 'Reactivated user',
  TAKE_DOWN_PRODUCT: 'Took down product',
  RESTORE_PRODUCT: 'Restored product',
};

/** D7 — a paginated, read-only view of the append-only admin_actions audit log. */
export default function AdminAuditLogPage() {
  const { actions, totalPages, pageNumber, loading, error, setPage } = useAdminActions();

  return (
    <AppLayout>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <div className="mb-2">
          <h1 className="text-2xl font-bold text-gray-900">Audit Log</h1>
          <p className="text-gray-500 text-sm mt-1">Every admin action, newest first. Append-only.</p>
        </div>
        <AdminNav />

        {loading && actions.length === 0 ? (
          <div className="flex items-center justify-center py-20">
            <svg className="animate-spin h-6 w-6 text-emerald-500" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
          </div>
        ) : error ? (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
            Failed to load the audit log.
          </div>
        ) : actions.length === 0 ? (
          <p className="text-center py-20 text-gray-500 text-sm">No admin actions yet.</p>
        ) : (
          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <table className="w-full text-left" aria-label="Admin audit log">
              <thead>
                <tr className="border-b border-gray-100 bg-gray-50/60">
                  {['When', 'Admin', 'Action', 'Target', 'Reason'].map((h) => (
                    <th key={h} className="py-3 px-6 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {actions.map((action) => (
                  <tr key={action.id}>
                    <td className="py-3 px-6 text-sm text-gray-500 whitespace-nowrap">
                      {new Date(action.createdAt).toLocaleString()}
                    </td>
                    <td className="py-3 px-6 text-sm text-gray-900">{action.admin.displayName}</td>
                    <td className="py-3 px-6 text-sm text-gray-700">{ACTION_LABELS[action.action] ?? action.action}</td>
                    <td className="py-3 px-6 text-sm text-gray-500">{action.targetType} #{action.targetId}</td>
                    <td className="py-3 px-6 text-sm text-gray-500 max-w-xs truncate" title={action.reason ?? ''}>
                      {action.reason ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <Pagination pageNumber={pageNumber} totalPages={totalPages} onPageChange={setPage} />
      </div>
    </AppLayout>
  );
}
