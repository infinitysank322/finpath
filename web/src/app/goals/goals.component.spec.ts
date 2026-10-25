import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { AllocationApiService } from '../allocation/allocation-api.service';
import { AllocationView } from '../allocation/allocation.models';
import { GoalApiService } from './goal-api.service';
import { GoalInput, GoalStatusView, GoalView } from './goal.models';
import { GoalsComponent } from './goals.component';

const home: GoalView = { id: 'g1', type: 'home', targetAmountPaise: 500000000, targetDate: '2031-03-25', currentAmountPaise: 125000000, priority: 3 };
const trip: GoalView = { id: 'g2', type: 'travel', targetAmountPaise: 10000000, targetDate: '2025-01-25', currentAmountPaise: 0, priority: 5 };
const done: GoalView = { id: 'g3', type: 'health', targetAmountPaise: 5000000, targetDate: '2027-01-25', currentAmountPaise: 5000000, priority: 5 };

const status = (s: Partial<GoalStatusView>): GoalStatusView => ({
  cyclesRemaining: 60, shortfallPaise: 0, monthlyNeedPaise: 0, plannedMonthlyPaise: 0, status: 'ON_TRACK', onTrack: true, instrumentClass: 'EQUITY_ALLOWED', ...s,
});

class FakeGoalApi {
  list$: Observable<GoalView[]> = of([home, trip, done]);
  created: GoalInput[] = [];
  replaced: { id: string; body: GoalInput }[] = [];
  removed: string[] = [];
  statusCalls: { id: string; planned: number }[] = [];
  list() { return this.list$; }
  create(b: GoalInput) { this.created.push(b); return of(home); }
  replace(id: string, body: GoalInput) { this.replaced.push({ id, body }); return of(home); }
  remove(id: string): Observable<void> { this.removed.push(id); return of(undefined); }
  status(id: string, planned: number) {
    this.statusCalls.push({ id, planned });
    switch (id) {
      case 'g1': return of(status({ monthlyNeedPaise: 625000, plannedMonthlyPaise: planned, cyclesRemaining: 53 }));
      case 'g2': return of(status({ status: 'PAST_DUE', onTrack: false, shortfallPaise: 10000000, monthlyNeedPaise: 10000000 }));
      default: return of(status({ status: 'ACHIEVED' }));
    }
  }
}

class FakeAllocationApi {
  result: Observable<AllocationView> = of({
    cycleStart: '2026-10-25', surplusPaise: 0, shortfallPaise: 0, allocatedPaise: 0, prepayShare: null,
    lines: [{ step: 'SAFE_GOALS', ref: 'g1', amountPaise: 700000 }, { step: 'DEBT_PREPAYMENT', ref: 'g2', amountPaise: 1 }],
  });
  current() { return this.result; }
}

describe('GoalsComponent', () => {
  let api: FakeGoalApi;
  let alloc: FakeAllocationApi;
  const setup = () => {
    TestBed.configureTestingModule({
      imports: [GoalsComponent],
      providers: [{ provide: GoalApiService, useValue: api }, { provide: AllocationApiService, useValue: alloc }],
    });
    const fixture = TestBed.createComponent(GoalsComponent);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => {
    api = new FakeGoalApi();
    alloc = new FakeAllocationApi();
  });

  it('sends the per-goal SAFE_GOALS allocation line as plannedMonthlyPaise, and 0 where there is none', () => {
    setup();
    expect(api.statusCalls).toEqual([{ id: 'g1', planned: 700000 }, { id: 'g2', planned: 0 }, { id: 'g3', planned: 0 }]);
  });

  it('shows progress as text as well as bar length, in lakh/crore form', () => {
    const { root } = setup();
    const first = root.querySelector('li.goal')!;
    expect(first.querySelector('.progress-text')?.textContent).toContain('₹12.5 L of ₹50 L, 25%');
    const bar = first.querySelector('[role=progressbar]')!;
    expect(bar.getAttribute('aria-valuenow')).toBe('25');
    expect(bar.getAttribute('aria-valuetext')).toContain('25 percent');
    expect((first.querySelector('.fill') as HTMLElement).style.width).toBe('25%');
  });

  it('shows the API monthly need and a status word with an icon, not colour alone', () => {
    const { root } = setup();
    const items = root.querySelectorAll('li.goal');
    expect(items[0].textContent).toContain('Needs ₹6,250 a month for 53 more cycles');
    expect(items[0].querySelector('.badge')?.textContent).toContain('On track');
    expect(items[0].querySelector('.badge [aria-hidden=true]')?.textContent).toBe('✓');
    expect(items[1].querySelector('.badge')?.textContent).toContain('Past due');
    expect(items[1].textContent).toContain('still to go');
    expect(items[2].querySelector('.badge')?.textContent).toContain('Achieved');
    expect(items[2].textContent).toContain('Nothing more needed');
  });

  it('shows no Behind verdict for a long-horizon goal with nothing allocated', () => {
    const orig = api.status.bind(api);
    api.status = (id: string, planned: number) => id === 'g1' ? of(status({ status: 'BEHIND', onTrack: false, monthlyNeedPaise: 625000, plannedMonthlyPaise: 0 })) : orig(id, planned);
    alloc.result = of({ cycleStart: '2026-10-25', surplusPaise: 0, shortfallPaise: 0, allocatedPaise: 0, prepayShare: null, lines: [] });
    const { root } = setup();
    const first = root.querySelectorAll('li.goal')[0];
    expect(first.querySelector('.badge')?.textContent).toContain('Not tracked yet');
    expect(first.textContent).not.toContain('Behind');
  });

  it('keeps going with a zero plan when the allocation is unavailable, and says so', () => {
    alloc.result = throwError(() => new HttpErrorResponse({ status: 404 }));
    const { root } = setup();
    expect(api.statusCalls.every((c) => c.planned === 0)).toBeTrue();
    expect(root.textContent).toContain('cycle plan is not available');
  });

  it('creates a goal in integer paise with zero saved by default', () => {
    const { fixture, root } = setup();
    (root.querySelector('#goal-add') as HTMLElement).click();
    fixture.detectChanges();
    const set = (id: string, v: string) => {
      const i = root.querySelector('#' + id) as HTMLInputElement;
      i.value = v;
      i.dispatchEvent(new Event('input'));
    };
    set('goal-target', '2,00,000');
    set('goal-date', '2028-04-25');
    fixture.detectChanges();
    (root.querySelector('button[type=submit]') as HTMLElement).click();
    expect(api.created).toEqual([{ type: 'emergency', targetAmountPaise: 20000000, targetDate: '2028-04-25', currentAmountPaise: 0, priority: 5 }]);
  });

  it('disables Save for a missing date or out-of-range priority', () => {
    const { fixture, root } = setup();
    (root.querySelector('#goal-add') as HTMLElement).click();
    fixture.detectChanges();
    const input = root.querySelector('#goal-target') as HTMLInputElement;
    input.value = '1000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect((root.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBeTrue();
    const d = root.querySelector('#goal-date') as HTMLInputElement;
    d.value = '2028-04-25';
    d.dispatchEvent(new Event('input'));
    const p = root.querySelector('#goal-priority') as HTMLInputElement;
    p.value = '11';
    p.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect((root.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('edits with a prefilled form and replaces by id', () => {
    const { fixture, root } = setup();
    (root.querySelector('li.goal button') as HTMLElement).click();
    fixture.detectChanges();
    expect((root.querySelector('#goal-target') as HTMLInputElement).value).toBe('5000000');
    (root.querySelector('button[type=submit]') as HTMLElement).click();
    expect(api.replaced[0].id).toBe('g1');
    expect(api.replaced[0].body.currentAmountPaise).toBe(125000000);
  });

  it('asks before deleting, then deletes', () => {
    const { fixture, root } = setup();
    const remove = () => Array.from(root.querySelectorAll('li.goal')[0].querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Remove')!;
    remove().click();
    fixture.detectChanges();
    expect(api.removed.length).toBe(0);
    const confirm = Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Confirm remove')!;
    confirm.click();
    expect(api.removed).toEqual(['g1']);
  });

  it('shows Please sign in on 401', () => {
    api.list$ = throwError(() => new HttpErrorResponse({ status: 401 }));
    const { root } = setup();
    expect(root.querySelector('.error')?.textContent).toContain('Please sign in');
  });
});
