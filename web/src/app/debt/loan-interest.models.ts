/** Mirrors LoanService.LoanDetailView / InstalmentView. Money is integer paise; rates are API strings. */
export interface InstalmentView {
  number: number;
  openingPaise: number;
  interestPaise: number;
  principalPaise: number;
  paymentPaise: number;
  closingPaise: number;
}

export interface LoanDetailView {
  id: string;
  lender: string;
  type: string;
  emiUsedPaise: number;
  emiDerived: boolean;
  effectiveAnnualRate: string;
  interestRemainingPaise: number;
  totalRemainingPayablePaise: number;
  instalmentsRemaining: number;
  closed: boolean;
  pastDue: boolean;
  balloonFinalPayment: boolean;
  instalments: InstalmentView[];
}
