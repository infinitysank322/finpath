import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
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
  budget: { status: 'OK', safeToSpendPerDayPaise: 150_000 },
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
});
