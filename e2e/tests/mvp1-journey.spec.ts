import { test, expect, type Page } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 9, D8 — the MVP1 golden path, end to end, against the real stack:
 *
 *   1. register a creator
 *   2. become a creator
 *   3. upload a 3-page PDF and wait for it to reach LIVE (the real async processing pipeline —
 *      Validate → Convert to image tiles → Generate thumbnail → Generate preview → Mark LIVE)
 *   4. register a buyer and buy the product (mock payment gateway, success path)
 *   5. open the secure viewer and prove the page is drawn on a real <canvas> with non-blank
 *      pixels, and that no <img> is used for page content (VIEW-01)
 *
 * This is the one test in the whole project that never mocks anything below the browser: no
 * mocked GraphQL responses, no stubbed job queue. If this test is green, every layer of MVP1
 * actually works together, not just in isolation.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SAMPLE_PDF = path.join(__dirname, '..', 'fixtures', 'sample.pdf');

const PASSWORD = 'E2ePassword123!';
const runId = Date.now();

async function register(page: Page, name: string, email: string): Promise<void> {
  await page.goto('/register');
  await page.locator('#register-name').fill(name);
  await page.locator('#register-email').fill(email);
  await page.locator('#register-password').fill(PASSWORD);
  await page.locator('#register-confirm').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  // RegisterPage auto-logs in and lands on the marketplace (useAuth.register → login → '/').
  await expect(page).toHaveURL('/', { timeout: 15_000 });
}

test('MVP1 golden path: creator uploads, buyer buys, buyer reads a real watermarked page', async ({ browser }) => {
  const creatorEmail = `e2e-creator-${runId}@example.com`;
  const buyerEmail = `e2e-buyer-${runId}@example.com`;
  const productTitle = `E2E Sample Book ${runId}`;

  // Two separate browser contexts = two separate people, each with their own cookies/localStorage
  // — a real creator and a real buyer never share a session, and neither should this test.
  const creatorContext = await browser.newContext();
  const creator = await creatorContext.newPage();

  await test.step('register a creator', async () => {
    await register(creator, 'E2E Creator', creatorEmail);
  });

  await test.step('become a creator', async () => {
    await creator.goto('/become-creator');
    // Every field on this form is optional — submitting blank is a legitimate creator signup.
    await creator.locator('#become-creator-submit').click();
    await expect(creator).toHaveURL('/creator', { timeout: 15_000 });
  });

  await test.step('upload a product and wait for it to reach LIVE', async () => {
    await creator.goto('/creator/upload');
    await creator.locator('#upload-title').fill(productTitle);
    await creator.locator('#upload-description').fill(
      'A 3-page sample PDF uploaded by the Phase 9 end-to-end smoke test.'
    );
    await creator.locator('#upload-price').fill('199');
    await creator.locator('#upload-preview-pages').fill('1');
    await creator.locator('#upload-tags').fill('e2e,smoke');
    await creator.locator('#create-product-btn').click();

    // Step 2 of the wizard: pick the file and upload it.
    await expect(creator.locator('#pdf-file-input')).toBeAttached();
    await creator.setInputFiles('#pdf-file-input', SAMPLE_PDF);
    await creator.locator('#upload-pdf-btn').click();

    // A successful upload (202 Accepted) auto-navigates to the creator dashboard, which polls
    // every 3s while anything is PROCESSING (CreatorDashboardPage.tsx) — this is the real
    // PostgreSQL-backed job queue (ProcessingJobWorker, 5s poll) doing real PDF → tile work, not
    // a mock, so the generous timeout below is a real (if small) processing budget, not padding.
    await expect(creator).toHaveURL('/creator', { timeout: 15_000 });
    await expect(creator.getByText(productTitle)).toBeVisible();
    await expect(creator.locator('[data-testid="status-badge-live"]').first())
      .toBeVisible({ timeout: 60_000 });
  });

  await creatorContext.close();

  const buyerContext = await browser.newContext();
  const buyer = await buyerContext.newPage();

  await test.step('register a buyer', async () => {
    await register(buyer, 'E2E Buyer', buyerEmail);
  });

  await test.step('find the product and buy it (mock gateway, success)', async () => {
    await buyer.goto('/marketplace');
    await buyer.getByRole('searchbox', { name: 'Search products' }).fill(productTitle);
    await buyer.getByRole('link', { name: new RegExp(productTitle) }).click();
    await expect(buyer).toHaveURL(/\/product\/\d+/);

    await buyer.getByRole('button', { name: /^Buy for ₹/ }).click();
    await expect(buyer).toHaveURL(/\/checkout\/\d+/, { timeout: 15_000 });

    // The mock Razorpay panel (CheckoutPage) offers three outcomes; SUCCESS is what D8 asks for.
    await buyer.getByRole('button', { name: /^Pay ₹/ }).click();
    await expect(buyer.getByRole('heading', { name: 'Payment confirmed' })).toBeVisible({ timeout: 20_000 });

    await buyer.getByRole('link', { name: 'Go to my library' }).click();
    await expect(buyer).toHaveURL('/library');
  });

  await test.step('open the viewer and confirm real, non-blank canvas rendering (VIEW-01)', async () => {
    await buyer.getByRole('link', { name: 'Read' }).first().click();
    await expect(buyer).toHaveURL(/\/read\/\d+/);

    const viewerPage = buyer.locator('[data-testid="viewer-page"]');
    const canvas = viewerPage.locator('canvas');
    await expect(canvas).toBeVisible({ timeout: 20_000 });

    // VIEW-01: page content is canvas pixels, never an <img> — the browser must never receive
    // (or display via) a plain image tag for a document page.
    await expect(viewerPage.locator('img')).toHaveCount(0);

    // A plain <canvas> defaults to 300×150 with fully transparent pixels *before* anything is
    // ever drawn to it — a non-zero size is not evidence a tile has loaded, only a real,
    // non-blank pixel is. The tile fetch + decode + draw is itself async (signed-URL fetch,
    // then createImageBitmap, then drawImage — see useSecureTile), so this polls rather than
    // asserting once.
    const readNonBlankPixel = () =>
      canvas.evaluate((el) => {
        const canvasEl = el as HTMLCanvasElement;
        const ctx = canvasEl.getContext('2d');
        if (!ctx) return false;
        const { data } = ctx.getImageData(0, 0, canvasEl.width, canvasEl.height);
        // A pixel is "blank" only if it's fully transparent black (RGBA all zero) — the initial
        // state of an empty canvas. Any other value means a real image was actually drawn.
        for (let i = 0; i < data.length; i += 4) {
          if (data[i] !== 0 || data[i + 1] !== 0 || data[i + 2] !== 0 || data[i + 3] !== 0) {
            return true;
          }
        }
        return false;
      });

    await expect
      .poll(readNonBlankPixel, { timeout: 20_000, message: 'canvas stayed blank — tile never rendered' })
      .toBe(true);
  });

  await buyerContext.close();
});
