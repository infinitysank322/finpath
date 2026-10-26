import { LoanDetailView, LoanInput, LoanView } from './loan.models';

/**
 * Test-only. Five ANONYMISED loans with fake round figures that deliberately differ from the golden sample fixture
 * (GoldenFixtureScanTest keeps those out of web/). The total is derived from the loans, never typed.
 */
export const SAMPLE_LOANS: LoanInput[] = [
  { lender: 'Lender A', type: 'emi', principalOutstandingPaise: 310_000_000, rate: '0.08', emiPaise: 2_740_000, emisLeft: 240, bulletDueDate: null, upfrontFeePaise: null, dueDay: null },
  { lender: 'Lender B', type: 'bullet', principalOutstandingPaise: 120_000_000, rate: '0.15', emiPaise: 1_110_000, emisLeft: null, bulletDueDate: '2031-10-25', upfrontFeePaise: null, dueDay: null },
  { lender: 'Lender C', type: 'card_emi', principalOutstandingPaise: 12_000_000, rate: '0.14', emiPaise: 1_000_000, emisLeft: 12, bulletDueDate: null, upfrontFeePaise: 50_000, dueDay: null },
  { lender: 'Lender D', type: 'emi', principalOutstandingPaise: 30_000_000, rate: '0.11', emiPaise: 705_500, emisLeft: 48, bulletDueDate: null, upfrontFeePaise: null, dueDay: null },
  { lender: 'Lender E', type: 'emi', principalOutstandingPaise: 15_000_000, rate: '0.10', emiPaise: 500_000, emisLeft: 36, bulletDueDate: null, upfrontFeePaise: null, dueDay: null },
];

export const SAMPLE_EMI_TOTAL_PAISE = SAMPLE_LOANS.reduce((a, l) => a + (l.emiPaise ?? 0), 0);

/** Rupee text the UI is expected to show for a paise amount (whole rupees, en-IN grouping). */
export const rupees = (paise: number): string => '₹' + (paise / 100).toLocaleString('en-IN');

export function viewOf(i: LoanInput, n: number): LoanView {
  return {
    id: `loan-${n}`, lender: i.lender, type: i.type, principalOutstandingPaise: i.principalOutstandingPaise, rate: i.rate,
    emiPaise: i.emiPaise, emiDerived: false, emisLeft: i.emisLeft, bulletDueDate: i.bulletDueDate,
    upfrontFeePaise: i.upfrontFeePaise ?? 0, dueDay: i.dueDay, createdAt: '2026-10-25T00:00:00Z',
  };
}

export const SAMPLE_VIEWS: LoanView[] = SAMPLE_LOANS.map(viewOf);

const rows = (n: number, pay: number) =>
  Array.from({ length: n }, (_, k) => ({ number: k + 1, openingPaise: 1000 * (n - k), interestPaise: 10, principalPaise: pay - 10, paymentPaise: pay, closingPaise: 1000 * (n - k - 1) }));

const base = { emiDerived: false, computedEmiComparison: null, closed: false, pastDue: false, balloonFinalPayment: false, payoffCycle: { start: '2027-10-25', endExclusive: '2027-11-25' } };

export const DETAIL_EMI: LoanDetailView = {
  ...base, id: 'loan-0', lender: 'Lender A', type: 'emi', emiUsedPaise: 2_740_000,
  computedEmiComparison: { label: 'Calculated from principal, rate and tenure (comparison only)', emiPaise: 2_736_100 },
  effectiveAnnualRate: '0.08', interestRemainingPaise: 301_700_000, totalRemainingPayablePaise: 611_700_000,
  instalmentsRemaining: 20, instalments: rows(20, 2_740_000),
};
export const DETAIL_BULLET: LoanDetailView = {
  ...base, id: 'loan-1', lender: 'Lender B', type: 'bullet', emiUsedPaise: 1_110_000, effectiveAnnualRate: '0.15',
  interestRemainingPaise: 150_000_000, totalRemainingPayablePaise: 270_000_000, instalmentsRemaining: 3, balloonFinalPayment: true,
  instalments: rows(3, 1_110_000),
};
export const DETAIL_CARD: LoanDetailView = {
  ...base, id: 'loan-2', lender: 'Lender C', type: 'card_emi', emiUsedPaise: 1_000_000, effectiveAnnualRate: '0.2231',
  interestRemainingPaise: 1_000_000, totalRemainingPayablePaise: 13_000_000, instalmentsRemaining: 12, instalments: rows(12, 1_000_000),
};
