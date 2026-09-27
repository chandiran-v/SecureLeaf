import type { PageLink } from '../types';

const SAFE_PROTOCOLS = new Set(['http:', 'https:', 'mailto:']);

/**
 * Defense in depth for PDF links (V8): the server already drops anything that isn't
 * http/https/mailto, but these become real `<a href>`s, so the browser checks again. A
 * `javascript:` URL slipping through would run script in our origin.
 */
export function isSafeLinkUrl(url: string | null | undefined): url is string {
  if (!url) return false;
  try {
    return SAFE_PROTOCOLS.has(new URL(url).protocol);
  } catch {
    return false;
  }
}

/** Links worth rendering: safe URL links, page links within the document, sane rectangles. */
export function usableLinks(links: PageLink[], pageCount: number | null): PageLink[] {
  return links.filter((link) => {
    if (!(link.width > 0 && link.height > 0)) return false;
    if (link.type === 'URL') return isSafeLinkUrl(link.url);
    return link.targetPage != null && link.targetPage >= 1 && (pageCount == null || link.targetPage <= pageCount);
  });
}
