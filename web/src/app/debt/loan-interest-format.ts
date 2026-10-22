import { InstalmentView } from './loan-interest.models';

export interface InstalmentTotals {
  principalPaise: number;
  interestPaise: number;
  paymentPaise: number;
}

/** Sum of integer paise taken straight from the API's instalments. Display reconciliation only, no rate maths. */
export function instalmentTotals(rows: InstalmentView[]): InstalmentTotals {
  let principalPaise = 0;
  let interestPaise = 0;
  let paymentPaise = 0;
  for (const r of rows) {
    principalPaise += r.principalPaise;
    interestPaise += r.interestPaise;
    paymentPaise += r.paymentPaise;
  }
  return { principalPaise, interestPaise, paymentPaise };
}

/** Screen-reader description of the whole chart. `fmt` is the INR formatter. */
export function describeInterest(rows: InstalmentView[], totals: InstalmentTotals, fmt: (paise: number) => string): string {
  if (rows.length === 0) return 'No remaining instalments, so there is no principal and interest chart.';
  const first = rows[0];
  const last = rows[rows.length - 1];
  return (
    `Stacked area chart of ${rows.length} remaining instalments, each split into principal and interest. ` +
    `Instalment ${first.number} has ${fmt(first.principalPaise)} principal and ${fmt(first.interestPaise)} interest; ` +
    `instalment ${last.number} has ${fmt(last.principalPaise)} principal and ${fmt(last.interestPaise)} interest. ` +
    `Totals: ${fmt(totals.principalPaise)} principal and ${fmt(totals.interestPaise)} interest.`
  );
}

const GROUPED = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

/** Exact INR to the paise with lakh/crore grouping (integer split, no rounding), for reconciliation tables. */
export function exactInr(paise: number): string {
  if (!Number.isFinite(paise)) return '';
  const abs = Math.abs(Math.trunc(paise));
  const rupees = Math.floor(abs / 100);
  const p = abs % 100;
  return `${paise < 0 ? '-' : ''}₹${GROUPED.format(rupees)}.${p < 10 ? '0' : ''}${p}`;
}
