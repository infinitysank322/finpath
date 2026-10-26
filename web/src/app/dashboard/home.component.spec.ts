import { HttpErrorResponse } from '@angular/common/http';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Observable, of, throwError, timer } from 'rxjs';
import { map } from 'rxjs/operators';
import { USER_A, tokenResponse } from '../auth/auth-testing';
import { AUTH_STORAGE, AuthSessionStore } from '../auth/session.store';
import { memoryStorage } from '../expense/queue-testing';
import { DashboardApiService } from './dashboard-api.service';
import { DashboardCacheService } from './dashboard-cache.service';
import { DashboardView } from './dashboard.models';
import { HomeComponent } from './home.component';

const view: DashboardView = {
  asOf: '2026-10-25',
  cycle: { start: '2026-10-01', endExclusive: '2026-11-01', lengthDays: 31, daysElapsed: 25, daysLeft: 6 },
  budget: {
    status: 'OK', budgetPaise: 1_234_500, spentPaise: 654_300, remainingPaise: 580_200, usedFraction: '0.5300',
    level: 'OK', safeToSpendPerDayPaise: 96_700,
  },
};

describe('HomeComponent offline behaviour (T79)', () => {
  let next: () => Observable<DashboardView>;
  const render = () => {
    const store = memoryStorage();
    TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [
        { provide: AUTH_STORAGE, useValue: store },
        { provide: DashboardApiService, useValue: { get: () => next() } },
      ],
    });
    TestBed.inject(AuthSessionStore).save(tokenResponse(USER_A, 'r'));
    const f = TestBed.createComponent(HomeComponent);
    f.detectChanges();
    return f;
  };
  const text = (f: ReturnType<typeof render>) => (f.nativeElement as HTMLElement).textContent!;
  const has = (f: ReturnType<typeof render>, sel: string) => !!(f.nativeElement as HTMLElement).querySelector(sel);

  it('shows the live dashboard with no staleness label and caches it', () => {
    next = () => of(view);
    const f = render();
    expect(text(f)).toContain('6 days left');
    expect(has(f, '[data-f=stale]')).toBeFalse();
    expect(TestBed.inject(DashboardCacheService).load()?.data).toEqual(view);
  });

  it('offline: renders the cached dashboard with a clear staleness label', () => {
    next = () => of(view);
    const first = render();
    first.destroy();
    next = () => throwError(() => new HttpErrorResponse({ status: 0 }));
    const f = TestBed.createComponent(HomeComponent);
    f.detectChanges();
    expect(has(f, '[data-f=stale]')).toBeTrue();
    expect(text(f)).toContain('Cannot reach FinPath');
    expect(text(f)).toContain('2026-10-25');
    expect(text(f)).toContain('6 days left');
  });

  it('offline with nothing cached: shows an error with retry, not a blank screen', () => {
    next = () => throwError(() => new HttpErrorResponse({ status: 0 }));
    const f = render();
    expect(has(f, '[data-f=failed]')).toBeTrue();
  });

  it('the service worker\'s 504 while offline also falls back to the cache', () => {
    next = () => of(view);
    render().destroy();
    next = () => throwError(() => new HttpErrorResponse({ status: 504 }));
    const f = TestBed.createComponent(HomeComponent);
    f.detectChanges();
    expect(has(f, '[data-f=stale]')).toBeTrue();
  });

  it('an API error is not presented as offline data', () => {
    next = () => of(view);
    render().destroy();
    next = () => throwError(() => new HttpErrorResponse({ status: 500 }));
    const f = TestBed.createComponent(HomeComponent);
    f.detectChanges();
    expect(has(f, '[data-f=stale]')).toBeFalse();
    expect(has(f, '[data-f=failed]')).toBeTrue();
  });

  it('T82: a 12 s cold first request shows a skeleton, never an error, then succeeds', fakeAsync(() => {
    next = () => timer(12_000).pipe(map(() => view));
    const f = render();
    expect(has(f, '[data-f=loading]')).toBeTrue();
    tick(11_999);
    f.detectChanges();
    expect(has(f, '[data-f=loading]')).toBeTrue();
    expect(has(f, '[data-f=failed]')).toBeFalse();
    tick(1);
    f.detectChanges();
    expect(has(f, '[data-f=loading]')).toBeFalse();
    expect(text(f)).toContain('6 days left');
  }));

  describe('cycle ring (T75)', () => {
    const el = (f: ReturnType<typeof render>, sel: string) => (f.nativeElement as HTMLElement).querySelector(sel);
    const field = (f: ReturnType<typeof render>, k: string) => el(f, `[data-f=${k}]`)?.textContent?.trim();

    it('shows API figures verbatim: spent, budget, remaining, days left, safe-to-spend, percent', () => {
      next = () => of(view);
      const f = render();
      expect(field(f, 'spent')).toBe('₹6,543');
      expect(field(f, 'budget')).toBe('₹12,345');
      expect(field(f, 'remaining')).toBe('₹5,802');
      expect(field(f, 'safe')).toBe('₹967');
      expect(field(f, 'days-left')).toContain('6 days left');
      expect(field(f, 'pct')).toBe('53.00%');
      expect(field(f, 'level')).toContain('On track');
    });

    it('ring arc equals the API fraction and has an aria text equivalent', () => {
      next = () => of(view);
      const f = render();
      expect(el(f, '.arc')!.getAttribute('stroke-dasharray')).toBe('53 100');
      const svg = el(f, '[data-f=ring]')!;
      expect(svg.getAttribute('role')).toBe('img');
      const label = svg.getAttribute('aria-label')!;
      expect(label).toContain('53.00% of budget used');
      expect(label).toContain('Spent ₹6,543 of ₹12,345');
      expect(label).toContain('6 days left');
      expect(label).toContain('₹967 per day');
    });

    it('over budget: ring is capped at one turn and the level is stated in words', () => {
      next = () => of({ ...view, budget: { ...view.budget, usedFraction: '1.2500', level: 'BREACH', remainingPaise: -300_000, safeToSpendPerDayPaise: 0 } });
      const f = render();
      expect(el(f, '.arc')!.getAttribute('stroke-dasharray')).toBe('100 100');
      expect(field(f, 'level')).toContain('Over budget');
      expect(field(f, 'pct')).toBe('125.00%');
    });

    it('warning level is stated in words, not colour alone', () => {
      next = () => of({ ...view, budget: { ...view.budget, usedFraction: '0.8500', level: 'WARN' } });
      expect(field(render(), 'level')).toContain('Warning');
    });

    it('NOT_SET_UP: no ring and no fabricated zero, just a prompt and days left', () => {
      next = () => of({ ...view, budget: { status: 'NOT_SET_UP', budgetPaise: null, spentPaise: null, remainingPaise: null, usedFraction: null, level: null, safeToSpendPerDayPaise: null } } as DashboardView);
      const f = render();
      expect(el(f, '[data-f=ring]')).toBeNull();
      expect(el(f, '[data-f=not-set-up]')).not.toBeNull();
      expect(field(f, 'days-left')).toContain('6 days left');
      expect(text(f)).not.toContain('₹0');
    });

    it('cycle is labelled by its own dates, not a calendar month name for the cycle', () => {
      next = () => of(view);
      expect(field(render(), 'cycle')).toBe('Cycle from 1 Oct, next cycle from 1 Nov');
    });
  });
});
