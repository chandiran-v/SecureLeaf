/**
 * Axios REST client for non-GraphQL endpoints (file upload/download).
 *
 * Why axios instead of fetch?
 * Axios provides:
 * 1. Request interceptors — cleanly attach the Bearer token to every request
 * 2. Upload progress events — the onUploadProgress callback that powers the
 *    progress bar in UploadProductPage
 * 3. Automatic JSON parsing — fetch requires res.json() on every response
 *
 * This client shares the same base URL as the GraphQL proxy: Vite forwards
 * /api → localhost:8080 in dev (vite.config.ts), and nginx does it in prod.
 */
import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { useAuthStore } from '../store/authStore';
import { recoverFromUnauthorized } from './session';
import { newCorrelationId, withReference } from './correlationId';

const restClient = axios.create({
  baseURL: '/api',
});

// Request interceptor: attach the access token and a fresh correlation id (D1) to every request.
restClient.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  config.headers['X-Correlation-Id'] = newCorrelationId();
  return config;
});

// Response interceptor: on 401, refresh the login once and retry, or end the session
// (SessionExpiredModal). Same rules as Apollo's errorLink, via lib/session.ts.
restClient.interceptors.response.use(undefined, async (error: AxiosError<{ code?: string }>) => {
  const config = error.config as (InternalAxiosRequestConfig & { _authRetried?: boolean }) | undefined;
  if (error.response?.status !== 401 || !config || config._authRetried) {
    // D1 — genuinely failing here (not a transparently-retried expired token): callers across
    // this app read `err.message` straight into their error banner (see UploadProductPage,
    // RegisterPage), so appending the reference here reaches the user for free, everywhere.
    const correlationId = config?.headers?.['X-Correlation-Id'] as string | undefined;
    error.message = withReference(error.message, correlationId);
    return Promise.reject(error);
  }
  const refreshed = await recoverFromUnauthorized(error.response.data?.code);
  if (!refreshed) return Promise.reject(error);
  config._authRetried = true;
  return restClient(config); // request interceptor attaches the new token
});

export default restClient;
