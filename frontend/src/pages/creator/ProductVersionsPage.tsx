import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@apollo/client';
import AppLayout from '../../components/layout/AppLayout';
import { PRODUCT_VERSIONS } from '../../graphql/queries/product.queries';
import { RETIRE_DOCUMENT_VERSION, RETRY_PROCESSING } from '../../graphql/mutations/product.mutations';
import restClient from '../../lib/restClient';
import type { DocumentVersion, DocumentVersionStatus, UpdatePolicy } from '../../types';

const MAX_SIZE = 50 * 1024 * 1024;

const STATUS_STYLES: Record<DocumentVersionStatus, { cls: string; label: string }> = {
  PROCESSING: { cls: 'bg-amber-50 text-amber-700', label: 'Processing' },
  READY: { cls: 'bg-emerald-50 text-emerald-700', label: 'Ready' },
  FAILED: { cls: 'bg-red-50 text-red-700', label: 'Failed' },
  RETIRED: { cls: 'bg-slate-100 text-slate-600', label: 'Retired' },
};

const POLICY_LABELS: Record<UpdatePolicy, string> = {
  NEW_BUYERS_ONLY: 'New buyers only',
  FREE_UPDATE_FOR_EXISTING: 'Free update for existing buyers',
};

type UploadState = 'idle' | 'uploading' | 'processing' | 'done' | 'failed';

/**
 * /creator/products/:id/versions — the version history and "Upload new version" flow
 * (Phase 15, D7).
 *
 * The product keeps selling its CURRENT version while a new one processes (D2), so this page is the
 * only place the in-flight version is visible. It polls while any version is PROCESSING or a
 * free-update migration is still moving buyers, and stops as soon as everything is settled.
 */
export default function ProductVersionsPage() {
  const { id: productId = '' } = useParams<{ id: string }>();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const { data, loading, error, refetch, startPolling, stopPolling } = useQuery<{ productVersions: DocumentVersion[] }>(
    PRODUCT_VERSIONS,
    { variables: { productId }, fetchPolicy: 'cache-and-network' },
  );
  const versions = data?.productVersions ?? [];
  const busy = versions.some((v) => v.status === 'PROCESSING' || v.migrationPending);

  // Polling is driven by what the server last said, not by local state, so a refresh of the page
  // mid-processing keeps showing progress.
  useEffect(() => {
    if (busy) startPolling(3000);
    else stopPolling();
    return () => stopPolling();
  }, [busy, startPolling, stopPolling]);

  const [retryMutation] = useMutation(RETRY_PROCESSING, { refetchQueries: [{ query: PRODUCT_VERSIONS, variables: { productId } }] });
  const [retireMutation] = useMutation(RETIRE_DOCUMENT_VERSION, { refetchQueries: [{ query: PRODUCT_VERSIONS, variables: { productId } }] });

  const [file, setFile] = useState<File | null>(null);
  const [policy, setPolicy] = useState<UpdatePolicy>('NEW_BUYERS_ONLY');
  const [progress, setProgress] = useState(0);
  const [uploadState, setUploadState] = useState<UploadState>('idle');
  const [message, setMessage] = useState<string | null>(null);

  const newest = versions[0];
  // Derive the flow's later states from the server's view of the newest version.
  const effectiveState: UploadState =
    uploadState === 'processing' && newest
      ? newest.status === 'READY' ? 'done' : newest.status === 'FAILED' ? 'failed' : 'processing'
      : uploadState;

  const onFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files?.[0] ?? null;
    setMessage(null);
    if (!picked) return;
    if (!picked.name.toLowerCase().endsWith('.pdf')) {
      setMessage('Only PDF files are accepted.');
      return;
    }
    if (picked.size > MAX_SIZE) {
      setMessage('File is too large. Maximum size is 50MB.');
      return;
    }
    setFile(picked);
  };

  const upload = async () => {
    if (!file) return;
    setUploadState('uploading');
    setProgress(0);
    setMessage(null);
    const form = new FormData();
    form.append('file', file);
    form.append('updatePolicy', policy);
    try {
      await restClient.post(`/products/${productId}/versions`, form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (evt) => {
          if (evt.total) setProgress(Math.round((evt.loaded / evt.total) * 100));
        },
      });
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      setUploadState('processing');
      await refetch();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : 'Upload failed');
      setUploadState('idle');
    }
  };

  return (
    <AppLayout>
      <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <Link to="/creator" className="text-sm text-emerald-700 hover:underline">← Back to dashboard</Link>
        <h1 className="text-2xl font-bold text-gray-900 mt-3">Versions</h1>
        <p className="text-gray-500 text-sm mt-1 mb-8">
          Your product keeps selling its current version while a new one is processed. Buyers keep the version they bought
          unless you choose to update them for free.
        </p>

        <section className="bg-white rounded-xl border border-gray-200 p-5 mb-8" aria-labelledby="upload-version-heading">
          <h2 id="upload-version-heading" className="text-base font-semibold text-gray-900 mb-3">Upload new version</h2>
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="version-file" className="block text-sm font-medium text-gray-700 mb-1">PDF file</label>
              <input
                id="version-file"
                ref={fileInputRef}
                type="file"
                accept=".pdf,application/pdf"
                onChange={onFileChange}
                disabled={effectiveState === 'uploading'}
                className="block w-full text-sm text-gray-600"
              />
            </div>
            <div>
              <label htmlFor="update-policy" className="block text-sm font-medium text-gray-700 mb-1">Existing buyers</label>
              <select
                id="update-policy"
                value={policy}
                onChange={(e) => setPolicy(e.target.value as UpdatePolicy)}
                className="block w-full rounded-lg border border-gray-200 px-3 py-2 text-sm"
              >
                <option value="NEW_BUYERS_ONLY">{POLICY_LABELS.NEW_BUYERS_ONLY}</option>
                <option value="FREE_UPDATE_FOR_EXISTING">{POLICY_LABELS.FREE_UPDATE_FOR_EXISTING}</option>
              </select>
            </div>
          </div>

          <div className="mt-4 flex items-center gap-4">
            <button
              type="button"
              onClick={() => void upload()}
              disabled={!file || effectiveState === 'uploading' || effectiveState === 'processing'}
              className="px-4 py-2 rounded-lg bg-emerald-600 text-white text-sm font-semibold hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Upload version
            </button>
            <div role="status" aria-live="polite" className="text-sm text-gray-600" data-testid="upload-status">
              {effectiveState === 'uploading' && `Uploading… ${progress}%`}
              {effectiveState === 'processing' && 'Processing the new version… the product stays live on its current version.'}
              {effectiveState === 'done' && 'The new version is ready and is now current.'}
              {effectiveState === 'failed' && 'Processing the new version failed. You can retry it below.'}
            </div>
          </div>
          {message && <p className="mt-3 text-sm text-red-600" role="alert">{message}</p>}
        </section>

        {loading && versions.length === 0 ? (
          <div className="h-32 rounded-xl bg-gray-100 animate-pulse" aria-busy="true" />
        ) : error ? (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700" role="alert">
            Failed to load versions. <button onClick={() => void refetch()} className="underline font-medium">Retry</button>
          </div>
        ) : (
          <div className="overflow-x-auto bg-white rounded-xl border border-gray-200">
            <table className="min-w-full text-sm" aria-label="Version history">
              <thead className="bg-gray-50 text-left text-xs uppercase text-gray-500">
                <tr>
                  <th className="py-3 px-4">Version</th>
                  <th className="py-3 px-4">Uploaded</th>
                  <th className="py-3 px-4">Pages</th>
                  <th className="py-3 px-4">Status</th>
                  <th className="py-3 px-4">Existing buyers</th>
                  <th className="py-3 px-4">Buyers on it</th>
                  <th className="py-3 px-4" />
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {versions.map((v) => {
                  const s = STATUS_STYLES[v.status];
                  const canRetire = !v.current && v.buyerCount === 0 && (v.status === 'READY' || v.status === 'FAILED');
                  return (
                    <tr key={v.id} data-testid={`version-row-${v.versionNumber}`}>
                      <td className="py-3 px-4 font-medium text-gray-900">
                        v{v.versionNumber}
                        {v.current && (
                          <span className="ml-2 text-[11px] px-2 py-0.5 rounded-full bg-emerald-600 text-white">Current</span>
                        )}
                      </td>
                      <td className="py-3 px-4 text-gray-500">
                        {new Date(v.createdAt).toLocaleDateString('en-IN', { dateStyle: 'medium' })}
                      </td>
                      <td className="py-3 px-4 text-gray-500">{v.pageCount ?? '—'}</td>
                      <td className="py-3 px-4">
                        <span className={`inline-flex px-2.5 py-1 rounded-full text-xs font-medium ${s.cls}`}>{s.label}</span>
                        {v.status === 'FAILED' && v.failureReason && (
                          <p className="text-[11px] text-red-600 mt-1 max-w-[220px] line-clamp-2" title={v.failureReason}>
                            {v.failureReason}
                          </p>
                        )}
                        {v.migrationPending && (
                          <p className="text-[11px] text-amber-700 mt-1">Updating existing buyers…</p>
                        )}
                      </td>
                      <td className="py-3 px-4 text-gray-500">{POLICY_LABELS[v.updatePolicy]}</td>
                      <td className="py-3 px-4 text-gray-700">{v.buyerCount}</td>
                      <td className="py-3 px-4 text-right whitespace-nowrap">
                        {v.status === 'FAILED' && (
                          <button
                            onClick={() => void retryMutation({ variables: { productId, versionId: v.id } })}
                            className="text-xs px-3 py-1.5 rounded-lg border border-amber-200 text-amber-700 hover:bg-amber-50"
                          >
                            Retry
                          </button>
                        )}
                        {canRetire && (
                          <button
                            onClick={() => void retireMutation({ variables: { productId, versionId: v.id } })}
                            className="ml-2 text-xs px-3 py-1.5 rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50"
                          >
                            Retire
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </AppLayout>
  );
}
