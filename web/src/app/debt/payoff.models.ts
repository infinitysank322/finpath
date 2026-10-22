/** Mirrors LoanService.PayoffPlanView / RollOffView. Money is integer paise; rates are API strings. */
export type PayoffStrategy = 'avalanche' | 'snowball';

export interface PayoffCycleDates {
  /** ISO date the closing cycle starts. */
  start: string;
  /** ISO date the closing cycle ends (exclusive). */
  endExclusive: string;
}

export interface PayoffStepView {
  loanId: string;
  lender: string;
  outstandingPaise: number;
  annualRate: string;
  monthlyPaymentPaise: number;
  /** 0-based cycle offset in which the loan closes. */
  payoffCycle: number;
  /** Cycle in which the final payment falls, computed by the API. */
  payoffCycleDates: PayoffCycleDates;
}

export interface PayoffPlanView {
  strategy: string;
  order: PayoffStepView[];
  totalInterestPaise: number;
  totalPaidPaise: number;
  cycles: number;
}

export interface RollOffStepView {
  cycleId: string;
  cycleEndExclusive: string;
  cycleIndex: number;
  loanIds: string[];
  freedPaise: number;
  cumulativeFreedPaise: number;
  redirectedPaise: number;
  cumulativeRedirectedPaise: number;
}

export interface RollOffView {
  redirectPct: string;
  steps: RollOffStepView[];
}
