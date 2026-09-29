/** Smallest payout a creator may request, in paise (mirrors payouts.min-amount-paise on the server, ₹100). */
export const MIN_PAYOUT_PAISE = 10_000;

/**
 * Turns what the creator typed ("250", "250.5", "1,200.00") into integer paise, or null if it isn't
 * a valid rupee amount. Done with string maths, never `parseFloat(x) * 100`: floats can't represent
 * most decimals exactly (0.1 + 0.2 !== 0.3), and money must never go through them (D8).
 */
export function parseRupeesToPaise(input: string): number | null {
  const cleaned = input.trim().replace(/,/g, '');
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(cleaned);
  if (!match) return null;
  const rupees = Number(match[1]);
  const paise = Number((match[2] ?? '').padEnd(2, '0'));
  const total = rupees * 100 + paise;
  return Number.isSafeInteger(total) ? total : null;
}

/** Validation message for the request form, or null when the amount can be submitted. */
export function validatePayoutAmount(input: string, availablePaise: number): string | null {
  const paise = parseRupeesToPaise(input);
  if (paise === null) return 'Enter an amount in rupees, like 500 or 500.50.';
  if (paise < MIN_PAYOUT_PAISE) return 'The minimum payout is ₹100.';
  if (paise > availablePaise) return 'That is more than your available balance.';
  return null;
}
