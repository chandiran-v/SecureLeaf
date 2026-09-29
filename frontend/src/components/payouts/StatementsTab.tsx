import { useState } from 'react';
import { useCreatorStatement } from '../../hooks/useCreatorStatement';
import restClient from '../../lib/restClient';
import { formatRupees } from '../../lib/formatPrice';

function currentMonth(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

/** Downloads the CSV through restClient so the Bearer token is attached (a plain <a href> can't send it). */
async function downloadCsv(month: string): Promise<void> {
  const res = await restClient.get<Blob>(`/creator/statements/${month}.csv`, { responseType: 'blob' });
  const url = URL.createObjectURL(res.data);
  const link = document.createElement('a');
  link.href = url;
  link.download = `secureleaf-statement-${month}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

/** Phase 09C D4 — the "Statements" tab: pick a month, see sales/refunds/fees/payouts, download the CSV. */
export default function StatementsTab() {
  const [month, setMonth] = useState(currentMonth());
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const { statement, loading, error } = useCreatorStatement(month);

  const handleDownload = async () => {
    setDownloadError(null);
    try {
      await downloadCsv(month);
    } catch {
      setDownloadError('Could not download the CSV. Please try again.');
    }
  };

  const totals = statement
    ? [
        { label: 'Gross sales', value: formatRupees(statement.grossSalesPaise) },
        { label: 'Refunds', value: formatRupees(-statement.refundsPaise) },
        { label: 'Platform fee', value: formatRupees(statement.platformFeePaise) },
        { label: 'Net earnings', value: formatRupees(statement.netEarningsPaise) },
        { label: 'Paid out', value: formatRupees(-statement.payoutsPaise) },
      ]
    : [];

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="statement-month" className="block text-sm font-medium text-gray-700">Month</label>
          <input
            id="statement-month"
            type="month"
            value={month}
            max={currentMonth()}
            onChange={(e) => e.target.value && setMonth(e.target.value)}
            className="mt-1 rounded-lg border border-gray-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
          />
        </div>
        <button
          type="button"
          onClick={() => void handleDownload()}
          className="rounded-lg border border-gray-200 px-4 py-2 text-sm font-semibold text-gray-700 hover:bg-gray-50"
        >
          Download CSV
        </button>
        {downloadError && <span role="alert" className="text-xs text-red-600">{downloadError}</span>}
      </div>

      {error ? (
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">Failed to load the statement.</p>
      ) : loading && !statement ? (
        <p className="py-10 text-center text-sm text-gray-500">Loading…</p>
      ) : statement ? (
        <>
          <dl className="grid grid-cols-2 gap-3 lg:grid-cols-5">
            {totals.map((t) => (
              <div key={t.label} className="rounded-xl border border-gray-200 bg-white px-4 py-3">
                <dt className="text-xs text-gray-500">{t.label}</dt>
                <dd className="text-lg font-bold text-gray-900">{t.value}</dd>
              </div>
            ))}
          </dl>

          {statement.lines.length === 0 ? (
            <p className="rounded-xl border border-dashed border-gray-200 py-10 text-center text-sm text-gray-500">
              No activity in {statement.month}.
            </p>
          ) : (
            <div className="overflow-x-auto rounded-xl border border-gray-200 bg-white">
              <table className="w-full text-left text-sm" aria-label="Statement lines">
                <thead>
                  <tr className="border-b border-gray-100 bg-gray-50/60 text-xs uppercase tracking-wide text-gray-500">
                    {['Date', 'Type', 'Description', 'Gross', 'Fee', 'Net'].map((h) => (
                      <th key={h} className="px-5 py-3 font-semibold">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {statement.lines.map((l, i) => (
                    <tr key={`${l.date}-${i}`}>
                      <td className="whitespace-nowrap px-5 py-3 text-gray-500">{new Date(l.date).toLocaleDateString()}</td>
                      <td className="px-5 py-3 text-gray-700">{l.type}</td>
                      <td className="px-5 py-3 text-gray-700">{l.description}</td>
                      <td className="px-5 py-3 text-gray-700">{formatRupees(l.grossPaise)}</td>
                      <td className="px-5 py-3 text-gray-700">{formatRupees(l.feePaise)}</td>
                      <td className="px-5 py-3 font-medium text-gray-900">{formatRupees(l.netPaise)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : null}
    </div>
  );
}
