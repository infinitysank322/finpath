import { GuardView } from './guard.models';

/** Mirrors api LoanService records. Money is paise (integer); rates are decimal-fraction strings ("0.085"). */
export type LoanType = 'emi' | 'bullet' | 'card_emi';

export interface LoanInput {
  lender: string;
  type: LoanType;
  principalOutstandingPaise: number;
  rate: string;
  emiPaise: number | null;
  emisLeft: number | null;
  bulletDueDate: string | null;
  upfrontFeePaise: number | null;
  /** Day of month the payment is due, 1-31, or null. Reminder dates are the API's. */
  dueDay: number | null;
}

export interface LoanView {
  id: string;
  lender: string;
  type: LoanType;
  principalOutstandingPaise: number;
  rate: string;
  emiPaise: number | null;
  emiDerived: boolean;
  emisLeft: number | null;
  bulletDueDate: string | null;
  upfrontFeePaise: number;
  dueDay: number | null;
  createdAt: string;
}

export interface InstalmentView {
  number: number;
  openingPaise: number;
  interestPaise: number;
  principalPaise: number;
  paymentPaise: number;
  closingPaise: number;
}

export interface PayoffCycle {
  start: string;
  endExclusive: string;
}

export interface LoanDetailView {
  id: string;
  lender: string;
  type: LoanType;
  emiUsedPaise: number;
  emiDerived: boolean;
  computedEmiComparison: { label: string; emiPaise: number } | null;
  effectiveAnnualRate: string;
  interestRemainingPaise: number;
  totalRemainingPayablePaise: number;
  instalmentsRemaining: number;
  payoffCycle: PayoffCycle | null;
  /** Present only if the API's detail response carries it. */
  dueDay?: number | null;
  closed: boolean;
  pastDue: boolean;
  balloonFinalPayment: boolean;
  instalments: InstalmentView[];
}

/** Body of a 409 from POST /loans. */
export interface DebtTrapBlockedBody {
  status: 409;
  error: 'debt_trap_blocked';
  guard: GuardView;
}
