import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { PayoffApiService } from './payoff-api.service';
import { PayoffComponent } from './payoff.component';
import { PayoffPlanView, PayoffStrategy, RollOffView } from './payoff.models';
import { ThemeService } from '../theme.service';

const plan: PayoffPlanView = {
  strategy: 'avalanche',
  order: [
    { loanId: 'a', lender: 'Bank A', outstandingPaise: 4400003, annualRate: '18', monthlyPaymentPaise: 77701, payoffCycle: 0, payoffCycleDates: { start: '2026-11-25', endExclusive: '2026-12-25' } },
    { loanId: 'b', lender: 'Bank B', outstandingPaise: 1400000007, annualRate: '9', monthlyPaymentPaise: 123457, payoffCycle: 11, payoffCycleDates: { start: '2027-10-25', endExclusive: '2027-11-25' } },
  ],
  totalInterestPaise: 12345601, totalPaidPaise: 1700000009, cycles: 12,
};
const roll: RollOffView = {
  redirectPct: '100',
  steps: [{ cycleId: '2026-11-25', cycleEndExclusive: '2026-12-25', cycleIndex: 1, loanIds: ['a'], freedPaise: 77701, cumulativeFreedPaise: 77701, redirectedPaise: 77701, cumulativeRedirectedPaise: 77701 }],
};

class FakeApi {
  asked: PayoffStrategy[] = [];
  planResult: Observable<PayoffPlanView> = of(plan);
  payoffPlan(s: PayoffStrategy) { this.asked.push(s); return this.planResult; }
  rollOffResult: Observable<RollOffView> = of(roll);
  rollOffCalls = 0;
  rollOff() { this.rollOffCalls++; return this.rollOffResult; }
}

describe('PayoffComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [PayoffComponent], providers: [{ provide: PayoffApiService, useValue: api }] });
    const fixture = TestBed.createComponent(PayoffComponent);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => (api = new FakeApi()));

  it('lists each loan with end cycle, freed date and INR payment in the table', () => {
    const { root } = setup();
    const rows = Array.from(root.querySelectorAll('table')[0].querySelectorAll('tbody tr')).map((r) => Array.from(r.children).map((c) => c.textContent!.trim()).join(' | '));
    expect(rows[0]).toBe('Bank A | Cycle 1 | 25/11/2026 | 25/11/2026 | ₹777');
    expect(rows[1]).toBe('Bank B | Cycle 12 | 25/10/2027 | Not available | ₹1,235');
  });

  it('gives the chart an accessible name that describes every loan', () => {
    const { root } = setup();
    const label = root.querySelector('.fp-chart')!.getAttribute('aria-label')!;
    expect(root.querySelector('.fp-chart')!.getAttribute('role')).toBe('img');
    expect(label).toContain('Bank A closes in cycle 1');
    expect(label).toContain('Bank B closes in cycle 12');
  });

  it('shows assumptions and the educational label', () => {
    const { root } = setup();
    expect(root.textContent).toContain('Educational');
    expect(root.textContent).toContain('Assumptions');
    expect(root.textContent).toContain('redirected at 100%');
  });

  it('reloads with the chosen strategy', () => {
    const { fixture, root } = setup();
    (root.querySelectorAll('.seg button')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.asked).toEqual(['avalanche', 'snowball']);
    expect(api.rollOffCalls).toBe(1);
    expect(root.querySelector('caption')!.textContent).toContain('Avalanche');
    expect(root.querySelectorAll('.seg button')[1].getAttribute('aria-pressed')).toBe('true');
  });

  it('shows an empty state without a chart', () => {
    api.planResult = of({ ...plan, order: [], cycles: 0 });
    const { root } = setup();
    expect(root.textContent).toContain('No active loans');
    expect(root.querySelector('.fp-chart')).toBeNull();
  });

  it('shows a friendly error', () => {
    api.planResult = throwError(() => new HttpErrorResponse({ status: 0 }));
    const { root } = setup();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('offline');
  });

  it('rebuilds chart options when the theme changes', () => {
    const { fixture } = setup();
    const c = fixture.componentInstance;
    const before = c.timelineOptions();
    const theme = TestBed.inject(ThemeService);
    // the starting theme follows the host's prefers-color-scheme, so flip to whichever is not active
    const start = theme.theme();
    theme.set(start === 'dark' ? 'light' : 'dark');
    expect(c.timelineOptions()).not.toBe(before);
    theme.set(start);
  });

  it('keeps end dates and static assumptions when roll-off fails', () => {
    api.rollOffResult = throwError(() => new HttpErrorResponse({ status: 500 }));
    const { root } = setup();
    expect(root.querySelector('[role=alert]')).toBeNull();
    const first = Array.from(root.querySelectorAll('table')[0].querySelectorAll('tbody tr')[0].children).map((c) => c.textContent!.trim());
    expect(first[2]).toBe('25/11/2026');
    expect(first[3]).toBe('Not available');
    expect(root.textContent).toContain('Assumptions');
    expect(root.textContent).toContain('rates do not change');
    expect(root.textContent).not.toContain('redirected at');
    expect(root.querySelector('.fp-chart')!.getAttribute('aria-label')).toContain('final payment in the cycle starting 25/11/2026');
  });

  it('applies only the latest strategy response', () => {
    const first = new Subject<PayoffPlanView>();
    const second = new Subject<PayoffPlanView>();
    const queue = [first, second];
    api.payoffPlan = (s: PayoffStrategy) => { api.asked.push(s); return queue.shift()!; };
    const { fixture, root } = setup();
    (root.querySelectorAll('.seg button')[1] as HTMLButtonElement).click();
    second.next({ ...plan, strategy: 'snowball', cycles: 7 });
    first.next({ ...plan, strategy: 'avalanche', cycles: 99 });
    fixture.detectChanges();
    expect(fixture.componentInstance.plan()!.cycles).toBe(7);
    expect(root.querySelector('caption')!.textContent).toContain('Snowball');
  });

  it('clears the plan when a strategy switch fails', () => {
    const { fixture, root } = setup();
    api.planResult = throwError(() => new HttpErrorResponse({ status: 0 }));
    api.payoffPlan = () => api.planResult;
    (root.querySelectorAll('.seg button')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.plan()).toBeNull();
    expect(root.querySelector('table')).toBeNull();
    expect(root.querySelector('[role=alert]')).not.toBeNull();
  });
});
