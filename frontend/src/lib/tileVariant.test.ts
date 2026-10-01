import { describe, expect, it } from 'vitest';
import { chooseTileVariant, DEFAULT_TILE_VARIANT } from './tileVariant';

const VARIANTS = [
  { name: 'DESKTOP', widthPx: 1240 },
  { name: 'MOBILE', widthPx: 900 },
];

describe('chooseTileVariant (Phase 16, D5)', () => {
  it('a small viewport at DPR 1 asks for MOBILE', () => {
    expect(chooseTileVariant(VARIANTS, 390, 1)).toBe('MOBILE');
  });

  it('a large viewport at DPR 2 asks for DESKTOP', () => {
    expect(chooseTileVariant(VARIANTS, 1100, 2)).toBe('DESKTOP');
  });

  it('a phone at DPR 3 needs more pixels than MOBILE has, so it asks for DESKTOP', () => {
    expect(chooseTileVariant(VARIANTS, 390, 3)).toBe('DESKTOP');
  });

  it('is exact at the boundary: a width that MOBILE exactly covers still gets MOBILE', () => {
    expect(chooseTileVariant(VARIANTS, 450, 2)).toBe('MOBILE');
    expect(chooseTileVariant(VARIANTS, 450.5, 2)).toBe('DESKTOP');
  });

  it('falls back to DESKTOP when the widest variant is still too small', () => {
    expect(chooseTileVariant(VARIANTS, 3000, 2)).toBe('DESKTOP');
  });

  it('does not depend on the order variants are listed in', () => {
    expect(chooseTileVariant([...VARIANTS].reverse(), 390, 1)).toBe('MOBILE');
  });

  it('picks a TABLET variant added later with no code change', () => {
    const withTablet = [...VARIANTS, { name: 'TABLET', widthPx: 1100 }];
    expect(chooseTileVariant(withTablet, 520, 2)).toBe('TABLET');
  });

  it('uses DESKTOP when the server sent no variants', () => {
    expect(chooseTileVariant(undefined, 390, 1)).toBe(DEFAULT_TILE_VARIANT);
    expect(chooseTileVariant([], 390, 1)).toBe(DEFAULT_TILE_VARIANT);
  });
});
