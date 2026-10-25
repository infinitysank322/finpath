import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ThemeService } from '../theme.service';
import { FiApiService } from './fi-api.service';
import { FiPlannerComponent } from './fi-planner.component';
import { FiPlanRequest, FiPlanView } from './fi.models';

const plan: FiPlanView = {
  yearsToFi: 15, inflatedMonthlyExpensePaise: 1, inflatedMonthlyPassivePaise: 0, grossCorpusPaise: 384000000, netCorpusPaise: 276000000,
  openingBalancePaise: 0, monthlySipPaise: 0,
  bands: [
    { band: 'LOW', annualReturn: '0.0825', requiredFirstMonthlyPaise: 9000000, projectedSavingsAtHorizonPaise: 200000000, fiYears: 18, fiDate: '2044-10-23' },
    { band: 'BASE', annualReturn: '0.10125', requiredFirstMonthlyPaise: 7000000, projectedSavingsAtHorizonPaise: 276000000, fiYears: 15, fiDate: '2041-10-23' },
    { band: 'HIGH', annualReturn: '0.12', requiredFirstMonthlyPaise: 5000000, projectedSavingsAtHorizonPaise: 350000000, fiYears: null, fiDate: null },
  ],
  assumptions: { inflation: '0.06', equityReturn: '0.12', debtReturnLow: '0.07', debtReturnHigh: '0.0825', fiMultiplier: 25, stepUp: '0.10' },
  disclaimer: 'Educational projection, not investment advice. Returns are assumptions, not guarantees.',
};

class FakeApi {
  asked: FiPlanRequest[] = [];
  result: Observable<FiPlanView> = of(plan);
  plan(r: FiPlanRequest) { this.asked.push(r); return this.result; }
}

describe('FiPlannerComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [FiPlannerComponent], providers: [{ provide: FiApiService, useValue: api }] });
    const fixture = TestBed.createComponent(FiPlannerComponent);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const type = (idx: number, v: string) => {
      const el = root.querySelectorAll('form input')[idx] as HTMLInputElement;
      el.value = v;
      el.dispatchEvent(new Event('input'));
    };
    const submit = () => {
      (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
      fixture.detectChanges();
    };
    return { fixture, root, type, submit };
  };
  beforeEach(() => (api = new FakeApi()));

  it('carries the educational label before any result', () => {
    const { root } = setup();
    expect(root.querySelector('.edu')!.textContent).toContain('not investment advice');
  });

  it('sends paise to the API and omits empty optional inputs', () => {
    const { type, submit } = setup();
    type(0, '60,000');
    type(1, '15');
    submit();
    expect(api.asked).toEqual([{ todayMonthlyExpensePaise: 6000000, todayMonthlyPassivePaise: undefined, openingBalancePaise: undefined, monthlySipPaise: undefined, stepUp: '0', yearsToFi: 15 }]);
  });

  it('rejects missing required input without calling the API', () => {
    const { root, submit, type } = setup();
    type(1, '15');
    submit();
    expect(api.asked.length).toBe(0);
    expect(root.querySelector('[role=alert]')!.textContent).toContain('monthly expense');
  });

  it('shows the three bands and assumptions in INR with a table equivalent', () => {
    const { root, type, submit } = setup();
    type(0, '60000');
    type(1, '15');
    submit();
    const rows = Array.from(root.querySelectorAll('table tbody tr')).map((r) => Array.from(r.children).map((c) => c.textContent!.trim()));
    expect(rows.length).toBe(3);
    expect(rows[0]).toEqual(['Low (8.25%)', '₹90,000', '₹20 L', '23/10/2044 (18 yrs)']);
    expect(rows[2][3]).toContain('Not reached');
    const assumptions = root.querySelector('.assumptions')!.textContent!;
    expect(assumptions).toContain('Inflation 6%');
    expect(assumptions).toContain('8.25%');
    expect(root.textContent).toContain('₹27.6 L');
  });

  it('gives the chart an accessible name describing every case', () => {
    const { root, type, submit } = setup();
    type(0, '60000');
    type(1, '15');
    submit();
    const wrap = root.querySelector('.chart-wrap')!;
    expect(wrap.getAttribute('role')).toBe('img');
    const label = wrap.getAttribute('aria-label')!;
    expect(label).toContain('Low case');
    expect(label).toContain('High case');
    expect(label).toContain('not a guarantee');
  });

  it('shows a friendly error and no result when the call fails', () => {
    api.result = throwError(() => new HttpErrorResponse({ status: 0 }));
    const { root, type, submit } = setup();
    type(0, '60000');
    type(1, '15');
    submit();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('offline');
    expect(root.querySelector('table')).toBeNull();
  });

  it('rebuilds chart options when the theme changes', () => {
    const { fixture, type, submit } = setup();
    type(0, '60000');
    type(1, '15');
    submit();
    const c = fixture.componentInstance;
    const before = c.options();
    const theme = TestBed.inject(ThemeService);
    // the starting theme follows the host's prefers-color-scheme, so flip to whichever is not active
    const start = theme.theme();
    theme.set(start === 'dark' ? 'light' : 'dark');
    expect(c.options()).not.toBe(before);
    theme.set(start);
  });
});
