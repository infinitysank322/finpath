import { OUT_CATEGORIES } from '../expense/expense.models';
import { fractionToPercentText } from '../budget/budget-format';
import { shortDate } from '../plan/plan-format';
import { ComparisonLine, CategorySpendLine, CycleSpan, SpendTag, TagSummary } from './spending.models';

export type MoneyFmt = (paise: number) => string;

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

export const categoryName = (code: string): string => OUT_CATEGORIES.find((c) => c.code === code)?.name ?? code;

const parse = (iso: string): Date => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
};
const iso = (d: Date): string => d.toISOString().slice(0, 10);

/** Last day of a cycle: the day before the exclusive end. Calendar arithmetic only. */
export function cycleLastDay(endExclusive: string): string {
  const d = parse(endExclusive);
  d.setUTCDate(d.getUTCDate() - 1);
  return iso(d);
}

/**
 * Label for a cycle. A cycle that starts on the 1st and ends before the 1st of the next month IS a calendar
 * month, so it may be named ("October 2026"). Every other cycle is labelled by its dates, never a month name.
 */
export function cycleLabel(start: string, endExclusive: string): string {
  const s = parse(start);
  const e = parse(endExclusive);
  const nextMonthFirst = new Date(Date.UTC(s.getUTCFullYear(), s.getUTCMonth() + 1, 1));
  if (s.getUTCDate() === 1 && e.getTime() === nextMonthFirst.getTime()) return `${MONTHS[s.getUTCMonth()]} ${s.getUTCFullYear()}`;
  return `${shortDate(start)} to ${shortDate(cycleLastDay(endExclusive))}`;
}

/** Label for the span of the baseline cycles, from the first one's start to the last one's end. */
export function baselineLabel(cycles: CycleSpan[]): string {
  if (cycles.length === 0) return '';
  const sorted = [...cycles].sort((a, b) => a.start.localeCompare(b.start));
  const first = sorted[0];
  const last = sorted[sorted.length - 1];
  if (sorted.length === 1) return cycleLabel(first.start, first.endExclusive);
  return `${shortDate(first.start)} to ${shortDate(cycleLastDay(last.endExclusive))}`;
}

export const TAG_NAMES: Record<SpendTag, string> = { NEED: 'Need', WANT: 'Want', WASTE: 'Waste', UNTAGGED: 'Not tagged' };

export interface TagRow {
  tag: SpendTag;
  name: string;
  paise: number;
}

export function tagRows(t: TagSummary): TagRow[] {
  const rows: [SpendTag, number][] = [['NEED', t.needPaise], ['WANT', t.wantPaise], ['WASTE', t.wastePaise], ['UNTAGGED', t.untaggedPaise]];
  return rows.map(([tag, paise]) => ({ tag, name: TAG_NAMES[tag], paise }));
}

export interface DonutRow {
  code: string;
  name: string;
  paise: number;
  /** "25" (a percentage, no sign) or null when the API sent no usable share. */
  percent: string | null;
}

export function donutRows(lines: CategorySpendLine[]): DonutRow[] {
  return lines.map((l) => ({ code: l.categoryCode, name: categoryName(l.categoryCode), paise: l.spentPaise, percent: fractionToPercentText(l.share) }));
}

export function describeDonut(rows: DonutRow[], total: string, label: string): string {
  if (rows.length === 0) return `No spending recorded for ${label}.`;
  const parts = rows.map((r) => (r.percent === null ? r.name : `${r.name} ${r.percent} percent`)).join('; ');
  return `Donut chart of spending by category for ${label}, ${total} in all. ${parts}.`;
}

/** Direction in words so a change never rests on colour: "Up by", "Down by" or "Same as". Sign handling only. */
export function changeText(deltaPaise: number, fmt: MoneyFmt): string {
  if (deltaPaise > 0) return `Up ${fmt(deltaPaise)}`;
  if (deltaPaise < 0) return `Down ${fmt(Math.abs(deltaPaise))}`;
  return 'No change';
}

const lowerFirst = (t: string): string => t.charAt(0).toLowerCase() + t.slice(1);

export interface ComparisonRow {
  code: string;
  name: string;
  currentPaise: number;
  averagePaise: number;
  deltaPaise: number;
}

export function comparisonRows(lines: ComparisonLine[]): ComparisonRow[] {
  return lines.map((l) => ({ code: l.categoryCode, name: categoryName(l.categoryCode), currentPaise: l.currentPaise, averagePaise: l.averagePaise, deltaPaise: l.deltaPaise }));
}

export function describeComparison(rows: ComparisonRow[], current: string, baseline: string, fmt: MoneyFmt): string {
  if (rows.length === 0) return `No spending to compare for ${current}.`;
  const parts = rows.map((r) => `${r.name}: ${fmt(r.currentPaise)} against an average of ${fmt(r.averagePaise)} (${lowerFirst(changeText(r.deltaPaise, fmt))})`);
  return `Bar chart comparing each category in ${current} with its average over ${baseline}. ${parts.join('; ')}.`;
}
