import { PayoffStepView, RollOffStepView } from './payoff.models';

/** "Cycle 1" for offset 0: the current cycle is the first one. Display labelling only. */
export function cycleLabel(payoffCycle: number): string {
  return `Cycle ${payoffCycle + 1}`;
}

/** Last roll-off step that lists the loan: the cycle from which its EMI no longer falls due. */
export function freedStepFor(loanId: string, steps: RollOffStepView[]): RollOffStepView | undefined {
  return steps.find((s) => s.loanIds.includes(loanId));
}

/** One table row per loan: lender, cycle in which it closes, date its EMI is freed (if the API gave one), payment. */
export interface PayoffRow {
  loanId: string;
  lender: string;
  cycle: string;
  /** Formatted start date of the cycle holding the final payment. */
  endsCycleStarting: string;
  freedFrom: string | null;
  monthlyPaymentPaise: number;
  annualRate: string;
}

export function payoffRows(order: PayoffStepView[], steps: RollOffStepView[], fmtDate: (iso: string) => string): PayoffRow[] {
  return order.map((o) => {
    const step = freedStepFor(o.loanId, steps);
    return {
      loanId: o.loanId,
      lender: o.lender,
      cycle: cycleLabel(o.payoffCycle),
      endsCycleStarting: fmtDate(o.payoffCycleDates.start),
      freedFrom: step ? fmtDate(step.cycleId) : null,
      monthlyPaymentPaise: o.monthlyPaymentPaise,
      annualRate: o.annualRate,
    };
  });
}

/** Screen-reader description of the whole timeline. `fmt` is the INR formatter. */
export function describeTimeline(rows: PayoffRow[], totalCycles: number, fmt: (paise: number) => string): string {
  if (rows.length === 0) return 'No active loans, so there is no payoff timeline.';
  const parts = rows.map(
    (r) => `${r.lender} closes in ${r.cycle.toLowerCase()}, final payment in the cycle starting ${r.endsCycleStarting}${r.freedFrom ? `, EMI freed from ${r.freedFrom}` : ''}, paying ${fmt(r.monthlyPaymentPaise)} per cycle`,
  );
  return `Payoff timeline over ${totalCycles} cycles. ${parts.join('. ')}.`;
}
