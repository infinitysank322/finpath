import { ChartPalette, rollOffOption, timelineOption } from './payoff-chart-options';
import { PayoffStepView, RollOffStepView } from './payoff.models';

const pal: ChartPalette = { text: '#111', muted: '#222', border: '#333', bar: '#444', step: '#555' };
const order: PayoffStepView[] = [
  { loanId: 'a', lender: 'Bank A', outstandingPaise: 1, annualRate: '18', monthlyPaymentPaise: 77701, payoffCycle: 2, payoffCycleDates: { start: '2027-01-25', endExclusive: '2027-02-25' } },
  { loanId: 'b', lender: 'Bank B', outstandingPaise: 1, annualRate: '9', monthlyPaymentPaise: 123457, payoffCycle: 11, payoffCycleDates: { start: '2027-10-25', endExclusive: '2027-11-25' } },
];
const steps: RollOffStepView[] = [
  { cycleId: '2026-11-25', cycleEndExclusive: '2026-12-25', cycleIndex: 3, loanIds: ['a'], freedPaise: 77701, cumulativeFreedPaise: 77701, redirectedPaise: 0, cumulativeRedirectedPaise: 0 },
  { cycleId: '2027-09-25', cycleEndExclusive: '2027-10-25', cycleIndex: 12, loanIds: ['b'], freedPaise: 123457, cumulativeFreedPaise: 201158, redirectedPaise: 0, cumulativeRedirectedPaise: 0 },
];
const fmt = (p: number) => `P${p}`;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Any = any;

describe('timelineOption', () => {
  const o = timelineOption(order, pal, fmt) as Any;
  it('lists lenders as categories (first loan on top) with 1-based cycle lengths', () => {
    expect(o.yAxis.data).toEqual(['Bank B', 'Bank A']);
    expect(o.series[0].data).toEqual([12, 3]);
  });
  it('has aria enabled and text labels so colour is not the only encoding', () => {
    expect(o.aria.enabled).toBeTrue();
    expect(o.series[0].label.show).toBeTrue();
    expect(o.series[0].label.formatter({ dataIndex: 1 })).toBe('C3');
    expect(o.series[0].itemStyle.borderWidth).toBeGreaterThan(0);
  });
  it('uses the supplied palette and formats money in the tooltip', () => {
    expect(o.series[0].itemStyle.color).toBe('#444');
    expect(o.series[0].tooltip.formatter({ dataIndex: 1 })).toContain('P77701 per cycle');
  });
  it('does not mutate its input', () => {
    expect(order[0].lender).toBe('Bank A');
  });
});

describe('rollOffOption', () => {
  const o = rollOffOption(steps, pal, fmt) as Any;
  it('plots cumulative freed paise as integers on a step line', () => {
    expect(o.series[0].step).toBe('end');
    expect(o.series[0].data).toEqual([77701, 201158]);
    expect(o.xAxis.data).toEqual(['C4', 'C13']);
  });
  it('formats value labels and axis through the money formatter', () => {
    expect(o.series[0].label.formatter({ value: 77701 })).toBe('P77701');
    expect(o.yAxis.axisLabel.formatter(5)).toBe('P5');
  });
});
