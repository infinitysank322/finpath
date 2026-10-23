/** Mirrors LoanService.SinkingFundView. Money is integer paise. */
export type SinkingFundStatus = 'FUNDED' | 'ON_TRACK' | 'BEHIND' | 'PAST_DUE';

export interface SinkingFundView {
  loanId: string;
  cyclesRemaining: number;
  outstandingPaise: number;
  setAsidePerCyclePaise: number;
  finalCycleAmountPaise: number;
  expectedSavedByNowPaise: number;
  savedDeltaPaise: number;
  catchUpPaise: number;
  status: SinkingFundStatus;
  behindSchedule: boolean;
}
