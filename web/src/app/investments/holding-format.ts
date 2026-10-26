import { AggregateClassView, ASSET_CLASSES, AssetClass } from './holding.models';

export const classLabel = (c: AssetClass | 'total'): string => ASSET_CLASSES.find((a) => a.code === c)?.label ?? 'Total';

/** A label is 1..60 characters after trimming. */
export function validName(name: string): boolean {
  const t = name.trim();
  return t.length >= 1 && t.length <= 60;
}

export interface AllocationSlice {
  assetClass: AssetClass;
  label: string;
  currentValuePaise: number;
  percent: number;
}

/**
 * Display rows for the donut and its table. Only classes the API reports with a value above zero are kept.
 * The percent is whole-number display of "class / API total" for labelling; the totals themselves are the API's.
 */
export function allocationSlices(classes: AggregateClassView[], totalPaise: number): AllocationSlice[] {
  return classes
    .filter((c) => c.assetClass !== 'total' && c.currentValuePaise > 0)
    .map((c) => ({
      assetClass: c.assetClass as AssetClass,
      label: classLabel(c.assetClass),
      currentValuePaise: c.currentValuePaise,
      percent: totalPaise > 0 ? Math.round((c.currentValuePaise * 100) / totalPaise) : 0,
    }));
}

/** Screen-reader description: every slice by name, amount and share. */
export function describeAllocation(slices: AllocationSlice[], total: string, fmt: (p: number) => string): string {
  if (slices.length === 0) return 'No holdings with a current value yet.';
  return `Allocation of ${total} by asset class. ` + slices.map((s) => `${s.label} ${fmt(s.currentValuePaise)}, ${s.percent} percent`).join('. ') + '.';
}
