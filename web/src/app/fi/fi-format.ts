import { FiBandView } from './fi.models';
import { shortDate } from '../plan/plan-format';

/**
 * API fraction string -> percent text by decimal-point shifting on the string ("0.0825" -> "8.25%",
 * "0.12" -> "12%"). Display only; no arithmetic.
 */
export function fractionToPercent(fraction: string): string {
  const m = /^(\d+)(?:\.(\d+))?$/.exec(fraction.trim());
  if (!m) return fraction;
  const frac = m[2] ?? '';
  const whole = (m[1] + frac.slice(0, 2).padEnd(2, '0')).replace(/^0+(?=\d)/, '');
  const rest = frac.slice(2).replace(/0+$/, '');
  return `${whole}${rest ? '.' + rest : ''}%`;
}

export function bandLabel(band: string): string {
  const b = band.toUpperCase();
  return b === 'LOW' ? 'Low' : b === 'BASE' ? 'Base' : b === 'HIGH' ? 'High' : band;
}

export function fiDateText(b: FiBandView): string {
  if (b.fiDate === null) return 'Not reached in the planning horizon';
  return b.fiYears === null ? shortDate(b.fiDate) : `${shortDate(b.fiDate)} (${b.fiYears} yrs)`;
}

export interface FiRow {
  band: string;
  returnText: string;
  requiredSipPaise: number;
  savingsPaise: number;
  fiDate: string;
}

export function fiRows(bands: FiBandView[]): FiRow[] {
  return bands.map((b) => ({
    band: bandLabel(b.band),
    returnText: fractionToPercent(b.annualReturn),
    requiredSipPaise: b.requiredFirstMonthlyPaise,
    savingsPaise: b.projectedSavingsAtHorizonPaise,
    fiDate: fiDateText(b),
  }));
}

/** Screen-reader description of the chart, built only from API figures. */
export function describeFi(rows: FiRow[], netCorpusPaise: number, years: number, fmt: (p: number) => string): string {
  if (rows.length === 0) return 'No projection available.';
  const parts = rows.map((r) => `${r.band} case at ${r.returnText} a year: ${fmt(r.savingsPaise)} after ${years} years`);
  return `Projected savings after ${years} years against a corpus target of ${fmt(netCorpusPaise)}. ${parts.join('. ')}. Educational range, not a guarantee.`;
}
