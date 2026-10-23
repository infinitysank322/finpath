import { PayoffCycleView, PrepayMode, PrepayQuery } from './prepay.models';

/** Text typed by the user, parsed to integer without floats. null when it is not a whole-number string. */
export function parseWhole(text: string): number | null {
  const t = text.trim();
  return /^\d{1,9}$/.test(t) ? Number(t) : null;
}

/** Rupees typed as "1,50,000" or "1500.50" to integer paise by string handling (input boundary, not maths). */
export function rupeesToPaise(text: string): number | null {
  const m = /^(\d{1,10})(?:\.(\d{1,2}))?$/.exec(text.replace(/[,\s₹]/g, ''));
  if (!m) return null;
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

export interface PrepayInputs {
  mode: PrepayMode;
  lump: string;
  after: string;
  newEmi: string;
  months: string;
}

/** Builds the request, or the reason it cannot be built. */
export function buildQuery(i: PrepayInputs): { query: PrepayQuery } | { error: string } {
  switch (i.mode) {
    case 'lump_sum': {
      const lump = rupeesToPaise(i.lump);
      if (lump === null || lump <= 0) return { error: 'Enter the lump sum in rupees, for example 100000.' };
      const q: PrepayQuery = { mode: i.mode, lumpPaise: lump };
      if (i.after.trim() !== '') {
        const a = parseWhole(i.after);
        if (a === null) return { error: 'Instalments paid before the lump sum must be a whole number.' };
        q.afterInstalment = a;
      }
      return { query: q };
    }
    case 'increase_emi': {
      const e = rupeesToPaise(i.newEmi);
      if (e === null || e <= 0) return { error: 'Enter the new monthly EMI in rupees.' };
      return { query: { mode: i.mode, newEmiPaise: e } };
    }
    case 'close_in': {
      const m = parseWhole(i.months);
      if (m === null || m <= 0) return { error: 'Enter the number of months as a whole number.' };
      return { query: { mode: i.mode, closeMonths: m } };
    }
  }
}

/** "Mar 2031" label for the cycle a payoff falls in; the ISO date comes from the API. */
export function cycleEndLabel(c: PayoffCycleView | null): string {
  if (!c) return 'Not available';
  const d = new Date(`${c.start}T00:00:00Z`);
  return d.toLocaleDateString('en-IN', { month: 'short', year: 'numeric', timeZone: 'UTC' }) + ' cycle';
}
