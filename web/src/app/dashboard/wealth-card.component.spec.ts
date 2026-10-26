import { TestBed } from '@angular/core/testing';
import { DashboardView } from './dashboard.models';
import { WealthCardComponent } from './wealth-card.component';
import { netWorthOption } from './wealth-chart-options';

const base: DashboardView = {
  asOf: '2026-10-25',
  cycle: { start: '2026-10-01', endExclusive: '2026-11-01', lengthDays: 31, daysElapsed: 25, daysLeft: 6 },
  budget: { status: 'NOT_SET_UP', budgetPaise: null, spentPaise: null, remainingPaise: null, usedFraction: null, level: null, safeToSpendPerDayPaise: null },
  netWorth: {
    nowPaise: 5_000_000,
    series: [
      { cycleStart: '2026-08-01', assetsPaise: 4_000_000, liabilitiesPaise: 500_000, netWorthPaise: 3_500_000 },
      { cycleStart: '2026-09-01', assetsPaise: 5_000_000, liabilitiesPaise: 400_000, netWorthPaise: 4_600_000 },
    ],
  },
  fi: {
    status: 'OK',
    bands: [
      { band: 'LOW', annualReturn: '0.07', fiYears: 20, fiDate: '2046-10-01' },
      { band: 'BASE', annualReturn: '0.10', fiYears: 17, fiDate: '2043-10-01' },
      { band: 'HIGH', annualReturn: '0.12', fiYears: null, fiDate: null },
    ],
    base: { fiYears: 17, fiDate: '2043-10-01' },
    deltaYearsVsLastCycle: null,
    assumptions: { inflation: '0.06', debtReturnHigh: '0.07', equityReturn: '0.12', fiMultiplier: 30, stepUp: '0.10' },
    disclaimer: 'Educational projection, not investment advice.',
  },
};

describe('WealthCardComponent (T76)', () => {
  const render = (v: DashboardView) => {
    TestBed.configureTestingModule({ imports: [WealthCardComponent] });
    const f = TestBed.createComponent(WealthCardComponent);
    f.componentRef.setInput('view', v);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  };
  const q = (el: HTMLElement, f: string) => el.querySelector(`[data-f=${f}]`);

  it('plots the API series as given and gives the chart a text description and a table', () => {
    const el = render(base);
    expect(q(el, 'nw-chart')?.getAttribute('aria-label')).toContain('01/08/2026');
    expect(q(el, 'nw-table')?.querySelectorAll('tbody tr').length).toBe(2);
    const opt = netWorthOption(base.netWorth!.series, (s) => s, { text: '', muted: '', border: '', line: '', target: '' }, String);
    expect((opt.series as { data: number[] }[])[0].data).toEqual([3_500_000, 4_600_000]);
  });

  it('shows the FI date with the range and the assumptions', () => {
    const el = render(base);
    expect(q(el, 'fi-base')?.textContent).toContain('01/10/2043');
    expect(q(el, 'fi-bands')?.textContent).toContain('01/10/2046');
    expect(q(el, 'fi-bands')?.textContent).toContain('Not reached');
    expect(q(el, 'fi-assumptions')?.textContent).toContain('Inflation 6%');
    expect(q(el, 'fi-disclaimer')?.textContent).toContain('not investment advice');
  });

  it('says the change vs last cycle is unavailable when the API sends null, and shows a number when present', () => {
    expect(q(render(base), 'fi-delta')?.textContent).toContain('not available yet');
    TestBed.resetTestingModule();
    const el = render({ ...base, fi: { ...base.fi!, deltaYearsVsLastCycle: -1 } });
    expect(q(el, 'fi-delta')?.textContent).toContain('Closer by 1 yr');
    TestBed.resetTestingModule();
    expect(q(render({ ...base, fi: { ...base.fi!, deltaYearsVsLastCycle: 2 } }), 'fi-delta')?.textContent).toContain('Further by 2 yrs');
    TestBed.resetTestingModule();
    expect(q(render({ ...base, fi: { ...base.fi!, deltaYearsVsLastCycle: 0 } }), 'fi-delta')?.textContent).toContain('No change');
  });

  it('handles an empty series and a not-set-up FI without errors', () => {
    const el = render({ ...base, netWorth: { nowPaise: 0, series: [] }, fi: { ...base.fi!, status: 'NOT_SET_UP', base: null, bands: [] } });
    expect(q(el, 'nw-empty')).toBeTruthy();
    expect(q(el, 'fi-not-set-up')).toBeTruthy();
  });
});
