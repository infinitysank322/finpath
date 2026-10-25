/** Mirrors the T65 API (HoldingController). Money is integer paise; totals come from /holdings/aggregate. */
export type AssetClass = 'epf' | 'ppf' | 'nps' | 'mf' | 'stocks' | 'fd' | 'gold';

export interface HoldingInput {
  assetClass: AssetClass;
  name: string; // 1..60 chars, a label only
  investedPaise: number;
  currentValuePaise: number;
  monthlyContributionPaise: number;
}

export interface HoldingView extends HoldingInput {
  id: string;
  createdAt?: string;
}

export interface AggregateClassView {
  assetClass: AssetClass | 'total';
  investedPaise: number;
  currentValuePaise: number;
  monthlyContributionPaise: number;
  count: number;
}

export interface HoldingAggregateView {
  classes: AggregateClassView[];
  total: AggregateClassView;
}

/** The closed list from the T65 design note; widening it needs an API migration first. */
export const ASSET_CLASSES: { code: AssetClass; label: string }[] = [
  { code: 'epf', label: 'EPF (provident fund)' },
  { code: 'ppf', label: 'PPF' },
  { code: 'nps', label: 'NPS' },
  { code: 'mf', label: 'Mutual funds' },
  { code: 'stocks', label: 'Stocks' },
  { code: 'fd', label: 'Fixed deposits' },
  { code: 'gold', label: 'Gold' },
];

export const NAME_MAX = 60;

/** Shown beside every name field. The API also rejects names with long digit runs. */
export const NAME_WARNING = 'Use a short label such as "Employer EPF". Do NOT enter account, folio, demat, PAN or UAN numbers.';
