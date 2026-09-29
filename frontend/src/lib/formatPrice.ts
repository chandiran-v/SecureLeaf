/** paise (integer, smallest currency unit) → a display string like "₹499" or "Free". */
export function formatPrice(pricePaise: number): string {
  if (pricePaise === 0) return 'Free';
  return '₹' + (pricePaise / 100).toLocaleString('en-IN', { minimumFractionDigits: 0 });
}

/** Like formatPrice, but zero is "₹0" (an amount), not "Free" (a price). Negative amounts keep their sign. */
export function formatRupees(paise: number): string {
  const sign = paise < 0 ? '−' : '';
  return sign + '₹' + (Math.abs(paise) / 100).toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
}
