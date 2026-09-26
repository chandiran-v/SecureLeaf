import { useState } from 'react';
import AppLayout from '../../components/layout/AppLayout';
import AdminNav from '../../components/admin/AdminNav';
import Pagination from '../../components/marketplace/Pagination';
import StatusBadge from '../../components/ui/StatusBadge';
import ReasonPromptDialog from '../../components/admin/ReasonPromptDialog';
import { useAdminUsers } from '../../hooks/useAdminUsers';
import type { AccountStatus, AdminUser, UserRole } from '../../types';

const ROLE_OPTIONS: UserRole[] = ['BUYER', 'CREATOR', 'ADMIN'];
const STATUS_OPTIONS: AccountStatus[] = ['ACTIVE', 'SUSPENDED', 'DEACTIVATED'];

function UserRow({
  user,
  onSuspend,
  onReactivate,
}: {
  user: AdminUser;
  onSuspend: (id: string) => void;
  onReactivate: (id: string) => Promise<void>;
}) {
  return (
    <tr className="hover:bg-gray-50 transition-colors">
      <td className="py-4 px-6">
        <p className="text-sm font-medium text-gray-900">{user.displayName}</p>
        <p className="text-xs text-gray-400">{user.email}</p>
      </td>
      <td className="py-4 px-6 text-sm text-gray-600">{user.roles.join(', ')}</td>
      <td className="py-4 px-6">
        <StatusBadge status={user.accountStatus} />
      </td>
      <td className="py-4 px-6 text-sm text-gray-500">
        <p>{user.productCount} product{user.productCount !== 1 ? 's' : ''}</p>
        <p className="text-[11px] text-gray-400">{user.purchaseCount} purchase{user.purchaseCount !== 1 ? 's' : ''}</p>
      </td>
      <td className="py-4 px-6">
        <div className="flex items-center gap-2 justify-end">
          {user.accountStatus === 'SUSPENDED' ? (
            <button
              onClick={() => void onReactivate(user.id)}
              className="text-xs px-3 py-1.5 rounded-lg border border-emerald-200 text-emerald-700 hover:bg-emerald-50 transition-colors"
            >
              Reactivate
            </button>
          ) : (
            !user.roles.includes('ADMIN') && (
              <button
                onClick={() => onSuspend(user.id)}
                className="text-xs px-3 py-1.5 rounded-lg border border-red-100 text-red-500 hover:bg-red-50 transition-colors"
              >
                Suspend
              </button>
            )
          )}
        </div>
      </td>
    </tr>
  );
}

/** D3/D4 — searchable, filterable user table. Suspend requires a reason (ReasonPromptDialog);
 *  reactivate is a single click. */
export default function AdminUsersPage() {
  const {
    users,
    totalPages,
    pageNumber,
    loading,
    error,
    search,
    role,
    status,
    suspendUser,
    suspendLoading,
    reactivateUser,
    setSearch,
    setRole,
    setStatus,
    setPage,
  } = useAdminUsers();

  const [suspendingUserId, setSuspendingUserId] = useState<string | null>(null);

  async function handleConfirmSuspend(reason: string) {
    if (!suspendingUserId) return;
    await suspendUser(suspendingUserId, reason);
    setSuspendingUserId(null);
  }

  return (
    <AppLayout>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <div className="mb-2">
          <h1 className="text-2xl font-bold text-gray-900">Users</h1>
          <p className="text-gray-500 text-sm mt-1">Search, filter, suspend and reactivate accounts.</p>
        </div>
        <AdminNav />

        {/* ── Filters ── */}
        <div className="flex flex-wrap items-center gap-3 mb-6">
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search email or name…"
            aria-label="Search users"
            className="flex-1 min-w-[220px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-900 placeholder-gray-400
                       focus:outline-none focus:ring-2 focus:ring-emerald-500/30"
          />
          <select
            value={role ?? ''}
            onChange={(e) => setRole((e.target.value || undefined) as UserRole | undefined)}
            aria-label="Filter by role"
            className="rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700"
          >
            <option value="">All roles</option>
            {ROLE_OPTIONS.map((r) => (
              <option key={r} value={r}>{r}</option>
            ))}
          </select>
          <select
            value={status ?? ''}
            onChange={(e) => setStatus((e.target.value || undefined) as AccountStatus | undefined)}
            aria-label="Filter by status"
            className="rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700"
          >
            <option value="">All statuses</option>
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>

        {loading && users.length === 0 ? (
          <div className="flex items-center justify-center py-20">
            <svg className="animate-spin h-6 w-6 text-emerald-500" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
          </div>
        ) : error ? (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
            Failed to load users.
          </div>
        ) : users.length === 0 ? (
          <p className="text-center py-20 text-gray-500 text-sm">No users match these filters.</p>
        ) : (
          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <table className="w-full text-left" aria-label="Users">
              <thead>
                <tr className="border-b border-gray-100 bg-gray-50/60">
                  {['User', 'Roles', 'Status', 'Activity', ''].map((h) => (
                    <th key={h} className="py-3 px-6 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {users.map((user) => (
                  <UserRow
                    key={user.id}
                    user={user}
                    onSuspend={setSuspendingUserId}
                    onReactivate={reactivateUser}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}

        <Pagination pageNumber={pageNumber} totalPages={totalPages} onPageChange={setPage} />

        {suspendingUserId && (
          <ReasonPromptDialog
            title="Suspend this user?"
            message="Their refresh tokens are revoked and open viewer sessions end immediately. They can be reactivated at any time."
            confirmLabel="Suspend"
            submitting={suspendLoading}
            onConfirm={(reason) => void handleConfirmSuspend(reason)}
            onCancel={() => setSuspendingUserId(null)}
          />
        )}
      </div>
    </AppLayout>
  );
}
