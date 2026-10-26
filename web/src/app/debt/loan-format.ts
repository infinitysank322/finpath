import { LoanType } from './loan.models';

export const LOAN_TYPES: { code: LoanType; label: string; hint: string }[] = [
  { code: 'emi', label: 'EMI loan', hint: 'Fixed monthly instalments (home, car, personal)' },
  { code: 'bullet', label: 'Bullet loan', hint: 'Interest each month, principal due on one date' },
  { code: 'card_emi', label: 'Card EMI', hint: 'Purchase converted to EMI on a credit card' },
];

export const loanTypeLabel = (t: LoanType): string => LOAN_TYPES.find((x) => x.code === t)?.label ?? t;

/** "8.5" (percent) -> "0.085" (API fraction). String handling only; null when malformed or not in [0, 100). */
export function pctToRate(text: string): string | null {
  const m = /^(\d{1,2})(?:\.(\d{1,8}))?$/.exec(text.trim().replace(/%$/, ''));
  if (!m) return null;
  const digits = m[1].padStart(2, '0') + (m[2] ?? '');
  return '0.' + digits;
}

/** "0.0850000000" (API fraction) -> "8.5%". String shifting only. */
export function rateToPctText(rate: string): string {
  const m = /^(\d+)(?:\.(\d+))?$/.exec(rate);
  if (!m) return '';
  const frac = (m[2] ?? '').padEnd(2, '0');
  const whole = String(Number(m[1] + frac.slice(0, 2)));
  const rest = frac.slice(2).replace(/0+$/, '');
  return rest ? `${whole}.${rest}%` : `${whole}%`;
}

/** "2031-10-25" -> "25/10/2031". No month names: the cycle is user-defined. */
export function dmy(iso: string): string {
  const [y, m, d] = iso.split('-');
  return `${d}/${m}/${y}`;
}

/** 1 -> "1st", 22 -> "22nd", 11 -> "11th". Text only; the day itself comes from the API. */
export function ordinalDay(n: number): string {
  const r = n % 100;
  if (r >= 11 && r <= 13) return `${n}th`;
  return `${n}${({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[n % 10] ?? 'th'}`;
}
