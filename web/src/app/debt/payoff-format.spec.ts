import { cycleLabel, describeTimeline, freedStepFor, payoffRows } from './payoff-format';
import { PayoffStepView, RollOffStepView } from './payoff.models';

const order: PayoffStepView[] = [
  { loanId: 'a', lender: 'Bank A', outstandingPaise: 4400003, annualRate: '18', monthlyPaymentPaise: 77701, payoffCycle: 0, payoffCycleDates: { start: '2026-11-25', endExclusive: '2026-12-25' } },
  { loanId: 'b', lender: 'Bank B', outstandingPaise: 90000007, annualRate: '9', monthlyPaymentPaise: 123457, payoffCycle: 11, payoffCycleDates: { start: '2027-10-25', endExclusive: '2027-11-25' } },
];
const steps: RollOffStepView[] = [
  { cycleId: '2026-11-25', cycleEndExclusive: '2026-12-25', cycleIndex: 1, loanIds: ['a'], freedPaise: 77701, cumulativeFreedPaise: 77701, redirectedPaise: 77701, cumulativeRedirectedPaise: 77701 },
];
const iso = (d: string) => d.split('-').reverse().join('/');

describe('payoff-format', () => {
  it('labels cycles from 1', () => {
    expect(cycleLabel(0)).toBe('Cycle 1');
    expect(cycleLabel(11)).toBe('Cycle 12');
  });
  it('finds the roll-off step for a loan', () => {
    expect(freedStepFor('a', steps)?.cycleIndex).toBe(1);
    expect(freedStepFor('b', steps)).toBeUndefined();
  });
  it('builds rows with dates only when the API supplied one', () => {
    const rows = payoffRows(order, steps, iso);
    expect(rows.map((r) => r.freedFrom)).toEqual(['25/11/2026', null]);
    expect(rows[1].monthlyPaymentPaise).toBe(123457);
  });
  it('takes end dates from the API and keeps them without roll-off steps', () => {
    expect(payoffRows(order, [], iso).map((r) => r.endsCycleStarting)).toEqual(['25/11/2026', '25/10/2027']);
    expect(payoffRows(order, [], iso)[0].freedFrom).toBeNull();
  });
  it('describes the timeline and the empty case', () => {
    const text = describeTimeline(payoffRows(order, steps, iso), 12, (p) => `P${p}`);
    expect(text).toContain('12 cycles');
    expect(text).toContain('Bank A closes in cycle 1, final payment in the cycle starting 25/11/2026, EMI freed from 25/11/2026');
    expect(describeTimeline([], 0, String)).toContain('No active loans');
  });
});
