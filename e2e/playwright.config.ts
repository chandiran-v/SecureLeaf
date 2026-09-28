import { defineConfig, devices } from '@playwright/test';

/**
 * Phase 9, D8 — runs against a real, already-running stack (docker compose up for
 * Postgres/Redis/MinIO, plus the backend and frontend dev servers — see e2e/README.md). This is
 * deliberately not a `webServer`-managed Playwright config: the journey exercises the full async
 * pipeline (upload → job queue → tile conversion → LIVE), which needs the real Spring Boot app,
 * not a mock.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
