/** paise (integer, smallest currency unit) → a display string like "₹499" or "Free". */
export function formatPrice(pricePaise: number): string {
  if (pricePaise === 0) return 'Free';
  return '₹' + (pricePaise / 100).toLocaleString('en-IN', { minimumFractionDigits: 0 });
}
