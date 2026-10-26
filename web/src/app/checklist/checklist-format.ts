/**
 * Cycle label for a cycle start date ("2026-10-25"). The cycle is user-defined, so a calendar-month name is used only
 * when the cycle starts on the 1st (it then is that month); otherwise the label is the numeric start date.
 */
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export function cycleLabel(iso: string): string {
  const [y, m, d] = iso.split('-');
  if (d === '01') return `${MONTHS[parseInt(m, 10) - 1] ?? m} ${y}`;
  return `Cycle from ${d}/${m}/${y}`;
}

/** Short label for a heatmap cell. */
export function cellLabel(iso: string): string {
  const [y, m, d] = iso.split('-');
  return d === '01' ? `${MONTHS[parseInt(m, 10) - 1] ?? m} ${y.slice(2)}` : `${d}/${m}`;
}
