/** Mirrors FiPlannerService.FiPlanView. Money is integer paise; rates and ratios are API decimal strings. */
export type FiBandName = 'LOW' | 'BASE' | 'HIGH';

export interface FiBandView {
  band: FiBandName;
  /** Annual return as a fraction string, e.g. "0.0825". */
  annualReturn: string;
  requiredFirstMonthlyPaise: number;
  projectedSavingsAtHorizonPaise: number;
  /** Null when the corpus is not reached within the solve horizon. */
  fiYears: number | null;
  /** ISO date, null together with fiYears. */
  fiDate: string | null;
}

export interface FiAssumptions {
  inflation: string;
  equityReturn: string;
  debtReturnLow: string;
  debtReturnHigh: string;
  fiMultiplier: number;
  stepUp: string;
}

export interface FiPlanView {
  yearsToFi: number;
  inflatedMonthlyExpensePaise: number;
  inflatedMonthlyPassivePaise: number;
  grossCorpusPaise: number;
  netCorpusPaise: number;
  openingBalancePaise: number;
  monthlySipPaise: number;
  bands: FiBandView[];
  assumptions: FiAssumptions;
  disclaimer: string;
}

/** Inputs sent to the API; amounts in paise. Optional ones are omitted when not entered. */
export interface FiPlanRequest {
  todayMonthlyExpensePaise: number;
  todayMonthlyPassivePaise?: number;
  openingBalancePaise?: number;
  monthlySipPaise?: number;
  /** Fraction string such as "0.10". */
  stepUp?: string;
  yearsToFi: number;
}
