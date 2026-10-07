/**
 * Turns a server-relative path the backend hands us (e.g. a signed tile URL "/api/viewer/tiles/…")
 * into a URL the browser can fetch.
 *
 * Same-origin deployments (Vite dev proxy, Caddy on one server): VITE_API_URL is unset or relative,
 * so the path is returned unchanged and resolves against the page's own origin.
 *
 * Split-origin deployments (frontend on Vercel, backend on Render): VITE_API_URL is absolute
 * ("https://secureleaf-backend.onrender.com/api"), so the path is resolved against THAT origin.
 * Without this, `fetch("/api/viewer/…")` would go to Vercel and get the SPA's index.html back.
 */
export function resolveApiUrl(path: string, apiBase: string | undefined = import.meta.env.VITE_API_URL): string {
  if (!apiBase || !/^https?:\/\//i.test(apiBase) || /^https?:\/\//i.test(path)) {
    return path;
  }
  return new URL(path, apiBase).toString();
}
