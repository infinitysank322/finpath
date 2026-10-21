import { Pipe, PipeTransform } from '@angular/core';

const PAISE_PER_RUPEE = 100;
const LAKH = 100_000;
const CRORE = 10_000_000;
/** From ₹10 L upward amounts abbreviate to lakh/crore; below that the full lakh-grouped figure is shown. */
const ABBREVIATE_FROM_RUPEES = 10 * LAKH;

const GROUPED = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

/** Formats a paise amount (integer) as INR with lakh/crore conventions. Display only; no money maths. */
@Pipe({ name: 'paiseInr', standalone: true })
export class PaiseInrPipe implements PipeTransform {
  transform(paise: number | null | undefined): string {
    if (paise === null || paise === undefined || !Number.isFinite(paise)) return '';
    const negative = paise < 0;
    const abs = Math.abs(paise);
    const rupees = Math.round(abs / PAISE_PER_RUPEE);
    let text: string;
    if (rupees >= ABBREVIATE_FROM_RUPEES) {
      // hundredths of a lakh, rounded in integers, so the unit is chosen from the rounded value
      const hundredthsLakh = Math.round(rupees / (LAKH / 100));
      text =
        hundredthsLakh >= 100 * 100
          ? `₹${trim(Math.round(rupees / (CRORE / 100)) / 100)} Cr`
          : `₹${trim(hundredthsLakh / 100)} L`;
    } else text = `₹${GROUPED.format(rupees)}`;
    return negative ? `-${text}` : text;
  }
}

function trim(value: number): string {
  return value.toFixed(2).replace(/\.?0+$/, '');
}
