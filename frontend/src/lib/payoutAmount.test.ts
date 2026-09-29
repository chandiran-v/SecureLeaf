import { describe, it, expect } from 'vitest';
import { MIN_PAYOUT_PAISE, parseRupeesToPaise, validatePayoutAmount } from './payoutAmount';

describe('parseRupeesToPaise', () => {
  it('converts with integer maths, no float error', () => {
    expect(parseRupeesToPaise('500')).toBe(50_000);
    expect(parseRupeesToPaise('500.5')).toBe(50_050);
    expect(parseRupeesToPaise('0.29')).toBe(29);      // 0.29 * 100 === 28.999999999999996 in floats
    expect(parseRupeesToPaise('1,200.00')).toBe(120_000);
  });

  it('rejects anything that is not a rupee amount', () => {
    for (const bad of ['', 'abc', '-5', '1.234', '1e3', '12.', '.5']) {
      expect(parseRupeesToPaise(bad)).toBeNull();
    }
  });
});

describe('validatePayoutAmount', () => {
  const available = 115_000;

  it('accepts an amount between the minimum and the available balance', () => {
    expect(validatePayoutAmount('100', available)).toBeNull();
    expect(validatePayoutAmount('1150', available)).toBeNull();
  });

  it('refuses below the minimum, above the balance and garbage', () => {
    expect(validatePayoutAmount('99.99', available)).toMatch(/minimum/i);
    expect(validatePayoutAmount('1150.01', available)).toMatch(/more than your available/i);
    expect(validatePayoutAmount('lots', available)).toMatch(/enter an amount/i);
    expect(MIN_PAYOUT_PAISE).toBe(10_000);
  });
});
