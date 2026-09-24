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
import axios from 'axios';
import { useAuthStore } from '../store/authStore';

const restClient = axios.create({
  baseURL: '/api',
});

// Request interceptor: attach the access token to every request
restClient.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export default restClient;
