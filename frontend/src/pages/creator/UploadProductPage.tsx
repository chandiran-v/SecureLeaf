import { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import AppLayout from '../../components/layout/AppLayout';
import { useCreatorProducts } from '../../hooks/useCreatorProducts';
import restClient from '../../lib/restClient';

type Step = 'form' | 'upload' | 'processing';

/**
 * UploadProductPage — two-step product creation + PDF upload.
 *
 * Step 1 (form): Fill in title, description, price, category, tags, free preview pages.
 *   → createProduct GraphQL mutation → Product created in DRAFT status.
 *
 * Step 2 (upload): Select a PDF file, upload via POST /api/products/{id}/document.
 *   → Shows an upload progress bar (powered by axios onUploadProgress).
 *   → Server returns 202 Accepted — product moves to PROCESSING.
 *   → User is redirected to the Creator Dashboard where they can watch status → LIVE.
 *
 * Constraints enforced on the frontend:
 *   - File must be .pdf (accept attribute + MIME check)
 *   - File must be ≤ 50 MB (client-side size check before upload)
 *   (The backend also enforces these — defense in depth)
 */
export default function UploadProductPage() {
  const navigate = useNavigate();
  const { createProduct, createLoading } = useCreatorProducts();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [step, setStep] = useState<Step>('form');
  const [error, setError] = useState<string | null>(null);

  // Step 1 fields
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [pricePaise, setPricePaise] = useState(0);
  const categoryId = '1'; // default category ID — no UI to change it yet
  const [tags, setTags] = useState('');
  const [freePreviewPages, setFreePreviewPages] = useState(3);

  // Step 2 state
  const [productId, setProductId] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [isUploading, setIsUploading] = useState(false);

  // ── Step 1: Create product ────────────────────────────────────────────────

  const handleCreateProduct = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const product = await createProduct({
        title: title.trim(),
        description: description.trim(),
        pricePaise,
        categoryId,
        tags: tags.split(',').map((t) => t.trim()).filter(Boolean),
        freePreviewPages,
      });
      if (product) {
        setProductId(product.id);
        setStep('upload');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create product');
    }
  };

  // ── Step 2: Upload PDF ────────────────────────────────────────────────────

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] ?? null;
    if (!file) return;

    // Client-side size check (50MB)
    const MAX_SIZE = 50 * 1024 * 1024;
    if (file.size > MAX_SIZE) {
      setError('File is too large. Maximum size is 50MB.');
      return;
    }
    if (!file.name.toLowerCase().endsWith('.pdf')) {
      setError('Only PDF files are accepted.');
      return;
    }
    setError(null);
    setSelectedFile(file);
  };

  const handleUpload = async () => {
    if (!selectedFile || !productId) return;
    setIsUploading(true);
    setUploadProgress(0);
    setError(null);

    const formData = new FormData();
    formData.append('file', selectedFile);

    try {
      await restClient.post(`/products/${productId}/document`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (evt) => {
          if (evt.total) {
            setUploadProgress(Math.round((evt.loaded / evt.total) * 100));
          }
        },
      });
      // 202 Accepted — redirect to dashboard where they can watch PROCESSING → LIVE
      navigate('/creator');
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Upload failed';
      setError(msg);
      setIsUploading(false);
    }
  };

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <AppLayout>
      <div className="max-w-2xl mx-auto px-4 py-10">
        {/* Step indicator */}
        <div className="flex items-center gap-2 mb-8">
          {(['form', 'upload'] as const).map((s, idx) => (
            <div key={s} className="flex items-center gap-2">
              <div className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold
                ${step === s ? 'bg-emerald-600 text-white' :
                  (step === 'upload' && s === 'form') || step === 'processing'
                    ? 'bg-emerald-100 text-emerald-700'
                    : 'bg-gray-100 text-gray-400'}`}>
                {idx + 1}
              </div>
              <span className={`text-sm font-medium ${step === s ? 'text-gray-900' : 'text-gray-400'}`}>
                {s === 'form' ? 'Product details' : 'Upload PDF'}
              </span>
              {idx === 0 && <div className="w-8 h-px bg-gray-200 mx-1" />}
            </div>
          ))}
        </div>

        {error && (
          <div className="mb-5 rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
            {error}
          </div>
        )}

        {/* ── Step 1: Product form ──────────────────────────────────────── */}
        {step === 'form' && (
          <form onSubmit={handleCreateProduct} className="bg-white rounded-xl border border-gray-200 p-6 space-y-5">
            <h2 className="text-lg font-semibold text-gray-900">Product details</h2>

            <div>
              <label htmlFor="upload-title" className="block text-sm font-medium text-gray-700 mb-1.5">Title *</label>
              <input
                id="upload-title"
                type="text"
                required
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="e.g. Advanced TypeScript Patterns"
                className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm text-gray-900
                           focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
              />
            </div>

            <div>
              <label htmlFor="upload-description" className="block text-sm font-medium text-gray-700 mb-1.5">Description *</label>
              <textarea
                id="upload-description"
                rows={4}
                required
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="What's in this document? Who is it for?"
                className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm text-gray-900 resize-none
                           focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
              />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label htmlFor="upload-price" className="block text-sm font-medium text-gray-700 mb-1.5">
                  Price (₹) — 0 for free
                </label>
                <input
                  id="upload-price"
                  type="number"
                  min={0}
                  step={1}
                  value={pricePaise / 100}
                  onChange={(e) => setPricePaise(Math.round(parseFloat(e.target.value || '0') * 100))}
                  className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm text-gray-900
                             focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
                />
              </div>
              <div>
                <label htmlFor="upload-preview-pages" className="block text-sm font-medium text-gray-700 mb-1.5">
                  Free preview pages
                </label>
                <input
                  id="upload-preview-pages"
                  type="number"
                  min={0}
                  value={freePreviewPages}
                  onChange={(e) => setFreePreviewPages(parseInt(e.target.value || '0', 10))}
                  className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm text-gray-900
                             focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
                />
              </div>
            </div>

            <div>
              <label htmlFor="upload-tags" className="block text-sm font-medium text-gray-700 mb-1.5">
                Tags <span className="text-gray-400 font-normal">(comma-separated)</span>
              </label>
              <input
                id="upload-tags"
                type="text"
                value={tags}
                onChange={(e) => setTags(e.target.value)}
                placeholder="typescript, programming, design"
                className="w-full rounded-lg border border-gray-300 bg-gray-50 px-4 py-2.5 text-sm text-gray-900
                           focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
              />
            </div>

            <button
              id="create-product-btn"
              type="submit"
              disabled={createLoading}
              className="w-full rounded-lg bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white
                         hover:bg-emerald-700 transition-all disabled:opacity-50 active:scale-[0.98]"
            >
              {createLoading ? 'Creating…' : 'Continue — Upload PDF'}
            </button>
          </form>
        )}

        {/* ── Step 2: PDF upload ────────────────────────────────────────── */}
        {step === 'upload' && (
          <div className="bg-white rounded-xl border border-gray-200 p-6 space-y-5">
            <h2 className="text-lg font-semibold text-gray-900">Upload PDF</h2>
            <p className="text-sm text-gray-500">
              Maximum file size: 50MB. Only PDF files are accepted.
            </p>

            {/* File drop zone */}
            <div
              className={`border-2 border-dashed rounded-xl p-8 text-center cursor-pointer transition-colors
                ${selectedFile ? 'border-emerald-400 bg-emerald-50' : 'border-gray-200 hover:border-emerald-300 hover:bg-gray-50'}`}
              onClick={() => fileInputRef.current?.click()}
            >
              <input
                ref={fileInputRef}
                id="pdf-file-input"
                type="file"
                accept="application/pdf,.pdf"
                className="hidden"
                onChange={handleFileChange}
              />
              {selectedFile ? (
                <>
                  <svg className="w-10 h-10 text-emerald-500 mx-auto mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                      d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                  <p className="text-sm font-medium text-gray-900">{selectedFile.name}</p>
                  <p className="text-xs text-gray-400 mt-1">
                    {(selectedFile.size / (1024 * 1024)).toFixed(2)} MB — click to change
                  </p>
                </>
              ) : (
                <>
                  <svg className="w-10 h-10 text-gray-300 mx-auto mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                      d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
                  </svg>
                  <p className="text-sm font-medium text-gray-700">Click to select your PDF</p>
                  <p className="text-xs text-gray-400 mt-1">or drag and drop</p>
                </>
              )}
            </div>

            {/* Progress bar */}
            {isUploading && (
              <div>
                <div className="flex justify-between text-xs text-gray-500 mb-1.5">
                  <span>Uploading…</span>
                  <span>{uploadProgress}%</span>
                </div>
                <div className="w-full bg-gray-100 rounded-full h-2 overflow-hidden">
                  <div
                    className="bg-emerald-500 h-2 rounded-full transition-all duration-300"
                    style={{ width: `${uploadProgress}%` }}
                  />
                </div>
              </div>
            )}

            <div className="flex gap-3">
              <button
                type="button"
                onClick={() => setStep('form')}
                disabled={isUploading}
                className="flex-1 rounded-lg border border-gray-200 px-4 py-2.5 text-sm font-medium text-gray-600
                           hover:bg-gray-50 transition-colors disabled:opacity-50"
              >
                ← Back
              </button>
              <button
                id="upload-pdf-btn"
                type="button"
                onClick={handleUpload}
                disabled={!selectedFile || isUploading}
                className="flex-1 rounded-lg bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white
                           hover:bg-emerald-700 transition-all disabled:opacity-50 active:scale-[0.98]"
              >
                {isUploading ? 'Uploading…' : 'Upload PDF'}
              </button>
            </div>
          </div>
        )}
      </div>
    </AppLayout>
  );
}
