/** Mirrors LoanService.PrepaymentView. Money is integer paise; the rate is an API string. */
export type PrepayMode = 'lump_sum' | 'increase_emi' | 'close_in';

export interface PrepayAssumptions {
  mode: PrepayMode;
  annualRate: string;
  principalOutstandingPaise: number;
  currentEmiPaise: number;
  tenureMonths: number;
  firstInstalmentFrom: string;
  note: string;
}

export interface PayoffCycleView {
  start: string;
  endExclusive: string;
}

export interface PrepaymentView {
  loanId: string;
  assumptions: PrepayAssumptions;
  newEmiPaise: number;
  baselineInstalments: number;
  newInstalments: number;
  instalmentsSaved: number;
  baselineInterestPaise: number;
  newInterestPaise: number;
  interestSavedPaise: number;
  baselinePayoffCycle: PayoffCycleView | null;
  newPayoffCycle: PayoffCycleView | null;
}

/** Query parameters the endpoint accepts; every money value is integer paise. */
export interface PrepayQuery {
  mode: PrepayMode;
  lumpPaise?: number;
  afterInstalment?: number;
  newEmiPaise?: number;
  closeMonths?: number;
}
