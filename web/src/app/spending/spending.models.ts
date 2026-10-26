/**
 * Wire shapes for the spending screens. Tag totals come from the existing GET /expenses/tag-summary (T39).
 * The two category contracts below are PROPOSED (no API endpoint exposes per-category totals or a 3-cycle
 * average yet); every figure arrives from the rules engine and is displayed, never computed, here.
 */
export type SpendTag = 'NEED' | 'WANT' | 'WASTE' | 'UNTAGGED';

export interface TagSummary {
  from: string;
  toExclusive: string;
  needPaise: number;
  wantPaise: number;
  wastePaise: number;
  untaggedPaise: number;
  totalPaise: number;
}

export interface CategorySpendLine {
  categoryCode: string;
  spentPaise: number;
  /** Decimal-fraction string such as "0.2500", supplied by the API. */
  share: string;
}

/** GET /spending/categories?cycleStart= (proposed). */
export interface CategoryBreakdown {
  cycleStart: string;
  cycleEndExclusive: string;
  totalPaise: number;
  lines: CategorySpendLine[];
}

export interface CycleSpan {
  start: string;
  endExclusive: string;
}

export interface ComparisonLine {
  categoryCode: string;
  currentPaise: number;
  /** Average of this category over the baseline cycles, computed by the API. */
  averagePaise: number;
  /** currentPaise - averagePaise, signed, computed by the API. */
  deltaPaise: number;
}

/** GET /spending/comparison?cycleStart= (proposed). baselineCycles lists the cycles that fed the average (up to 3). */
export interface SpendingComparison {
  cycleStart: string;
  cycleEndExclusive: string;
  baselineCycles: CycleSpan[];
  lines: ComparisonLine[];
}
