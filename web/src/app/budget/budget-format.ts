import { formatExactPaise } from '../expense/amount-entry';

/**
 * "0.8500" -> "85", "0.857" -> "85.7", "1.25" -> "125", "0" -> "0". The decimal point moves two places by
 * string handling only: no parsing to a number, no division or multiplication. Returns null for null/malformed.
 */
export function fractionToPercentText(fraction: string | null): string | null {
  if (fraction === null) return null;
  const m = /^(\d+)(?:\.(\d*))?$/.exec(fraction.trim());
  if (!m) return null;
  const frac = (m[2] ?? '').padEnd(2, '0');
  const whole = (m[1] + frac.slice(0, 2)).replace(/^0+(?=\d)/, '');
  const rest = frac.slice(2).replace(/0+$/, '');
  return rest === '' ? whole : `${whole}.${rest}`;
}

/** CSS bar width: the same text, capped at 100% by comparing digit strings (no arithmetic). */
export function barWidthText(percent: string | null): string {
  if (percent === null) return '0%';
  const whole = percent.split('.')[0];
  return whole.length >= 3 ? '100%' : `${percent}%`;
}

/** API remainingPaise as text. Only the sign is handled for display (a negative remainder reads "Over by"); no maths. */
export function remainingText(remainingPaise: number): string {
  return remainingPaise < 0 ? `Over by ${formatExactPaise(Math.abs(remainingPaise))}` : `${formatExactPaise(remainingPaise)} left`;
}
