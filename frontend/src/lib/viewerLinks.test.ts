import { describe, it, expect } from 'vitest';
import { isSafeLinkUrl, usableLinks } from './viewerLinks';
import type { PageLink } from '../types';

const box = { left: 0.1, top: 0.1, width: 0.2, height: 0.05 };

describe('isSafeLinkUrl', () => {
  it('allows http, https and mailto', () => {
    expect(isSafeLinkUrl('https://example.com/a')).toBe(true);
    expect(isSafeLinkUrl('http://example.com')).toBe(true);
    expect(isSafeLinkUrl('mailto:a@example.com')).toBe(true);
  });

  it('rejects script and local schemes, garbage, and nothing', () => {
    expect(isSafeLinkUrl('javascript:alert(1)')).toBe(false);
    expect(isSafeLinkUrl('JAVASCRIPT:alert(1)')).toBe(false);
    expect(isSafeLinkUrl('data:text/html,<b>x</b>')).toBe(false);
    expect(isSafeLinkUrl('file:///etc/passwd')).toBe(false);
    expect(isSafeLinkUrl('not a url')).toBe(false);
    expect(isSafeLinkUrl(null)).toBe(false);
  });
});

describe('usableLinks', () => {
  it('keeps safe URL links and in-range page links only', () => {
    const links: PageLink[] = [
      { ...box, type: 'URL', url: 'https://ok.example', targetPage: null },
      { ...box, type: 'URL', url: 'javascript:alert(1)', targetPage: null },
      { ...box, type: 'PAGE', url: null, targetPage: 3 },
      { ...box, type: 'PAGE', url: null, targetPage: 99 }, // beyond the document
      { ...box, width: 0, type: 'URL', url: 'https://zero.example', targetPage: null },
    ];

    expect(usableLinks(links, 10)).toEqual([links[0], links[2]]);
  });
});
