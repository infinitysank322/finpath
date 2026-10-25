import { LoanDetailView, LoanInput, LoanView } from './loan.models';

/**
 * Test-only. Five ANONYMISED loans. The home (EMI 33,500.00) and gold (monthly interest 33,333.00) rows reuse the
 * loan.* figures of api/src/test/resources/golden/persona.sample.properties; the other three are fake round figures
 * chosen so the stated EMIs sum to that file's emi.total_paise (8888800). No persona data lives in the repo.
 */
export const SAMPLE_EMI_TOTAL_PAISE = 8_888_800;

export const SAMPLE_LOANS: LoanInput[] = [
  { lender: 'Lender A', type: 'emi', principalOutstandingPaise: 400_000_000, rate: '0.08', emiPaise: 3_350_000, emisLeft: 240, bulletDueDate: null, upfrontFeePaise: null },
  { lender: 'Lender B', type: 'bullet', principalOutstandingPaise: 266_664_000, rate: '0.15', emiPaise: 3_333_300, emisLeft: null, bulletDueDate: '2031-10-25', upfrontFeePaise: null },
  { lender: 'Lender C', type: 'card_emi', principalOutstandingPaise: 12_000_000, rate: '0.14', emiPaise: 1_000_000, emisLeft: 12, bulletDueDate: null, upfrontFeePaise: 50_000 },
  { lender: 'Lender D', type: 'emi', principalOutstandingPaise: 30_000_000, rate: '0.11', emiPaise: 705_500, emisLeft: 48, bulletDueDate: null, upfrontFeePaise: null },
  { lender: 'Lender E', type: 'emi', principalOutstandingPaise: 15_000_000, rate: '0.10', emiPaise: 500_000, emisLeft: 36, bulletDueDate: null, upfrontFeePaise: null },
];

export function viewOf(i: LoanInput, n: number): LoanView {
  return {
    id: `loan-${n}`, lender: i.lender, type: i.type, principalOutstandingPaise: i.principalOutstandingPaise, rate: i.rate,
    emiPaise: i.emiPaise, emiDerived: false, emisLeft: i.emisLeft, bulletDueDate: i.bulletDueDate,
    upfrontFeePaise: i.upfrontFeePaise ?? 0, createdAt: '2026-10-25T00:00:00Z',
  };
}

export const SAMPLE_VIEWS: LoanView[] = SAMPLE_LOANS.map(viewOf);

const rows = (n: number, pay: number) =>
  Array.from({ length: n }, (_, k) => ({ number: k + 1, openingPaise: 1000 * (n - k), interestPaise: 10, principalPaise: pay - 10, paymentPaise: pay, closingPaise: 1000 * (n - k - 1) }));

const base = { emiDerived: false, computedEmiComparison: null, closed: false, pastDue: false, balloonFinalPayment: false, payoffCycle: { start: '2027-10-25', endExclusive: '2027-11-25' } };

export const DETAIL_EMI: LoanDetailView = {
  ...base, id: 'loan-0', lender: 'Lender A', type: 'emi', emiUsedPaise: 3_350_000,
  computedEmiComparison: { label: 'Calculated from principal, rate and tenure (comparison only)', emiPaise: 3_346_200 },
  effectiveAnnualRate: '0.08', interestRemainingPaise: 402_900_000, totalRemainingPayablePaise: 802_900_000,
  instalmentsRemaining: 20, instalments: rows(20, 3_350_000),
};
export const DETAIL_BULLET: LoanDetailView = {
  ...base, id: 'loan-1', lender: 'Lender B', type: 'bullet', emiUsedPaise: 3_333_300, effectiveAnnualRate: '0.15',
  interestRemainingPaise: 200_000_000, totalRemainingPayablePaise: 466_664_000, instalmentsRemaining: 3, balloonFinalPayment: true,
  instalments: rows(3, 3_333_300),
};
export const DETAIL_CARD: LoanDetailView = {
  ...base, id: 'loan-2', lender: 'Lender C', type: 'card_emi', emiUsedPaise: 1_000_000, effectiveAnnualRate: '0.2231',
  interestRemainingPaise: 1_000_000, totalRemainingPayablePaise: 13_000_000, instalmentsRemaining: 12, instalments: rows(12, 1_000_000),
};
