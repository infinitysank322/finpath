import { TestBed } from '@angular/core/testing';
import { USER_A, USER_B, tokenResponse } from '../auth/auth-testing';
import { AUTH_STORAGE, AuthSessionStore } from '../auth/session.store';
import { memoryStorage } from '../expense/queue-testing';
import { DashboardCacheService, dashboardCacheKeyFor } from './dashboard-cache.service';
import { DashboardView } from './dashboard.models';

const view = (asOf = '2026-10-25'): DashboardView => ({
  asOf,
  cycle: { start: '2026-10-01', endExclusive: '2026-11-01', lengthDays: 31, daysElapsed: 25, daysLeft: 6 },
  budget: { status: 'OK', budgetPaise: 1_234_500, spentPaise: 654_300, remainingPaise: 580_200, usedFraction: '0.5300', level: 'OK', safeToSpendPerDayPaise: 150_000 },
});

describe('DashboardCacheService', () => {
  let store: ReturnType<typeof memoryStorage>;
  let session: AuthSessionStore;
  let cache: DashboardCacheService;

  beforeEach(() => {
    store = memoryStorage();
    TestBed.configureTestingModule({ providers: [{ provide: AUTH_STORAGE, useValue: store }] });
    session = TestBed.inject(AuthSessionStore);
    cache = TestBed.inject(DashboardCacheService);
  });

  it('round-trips the last dashboard for the signed-in user', () => {
    session.save(tokenResponse(USER_A, 'r'));
    cache.save(view(), 1234);
    expect(cache.load()).toEqual({ data: view(), savedAt: 1234 });
  });

  it('never returns another user\'s dashboard', () => {
    session.save(tokenResponse(USER_A, 'r'));
    cache.save(view());
    session.save(tokenResponse(USER_B, 'r2'));
    expect(cache.load()).toBeNull();
  });

  it('saves nothing and loads nothing when signed out', () => {
    cache.save(view());
    expect(Object.keys(store.data)).toEqual([]);
    expect(cache.load()).toBeNull();
  });

  it('drops an entry cached before the ring, which has no budget figures', () => {
    session.save(tokenResponse(USER_A, 'r'));
    const old = view();
    store.setItem(dashboardCacheKeyFor(USER_A), JSON.stringify({ savedAt: 1, data: { ...old, budget: { status: 'OK', safeToSpendPerDayPaise: 1 } } }));
    expect(cache.load()).toBeNull();
  });

  it('ignores corrupt storage', () => {
    session.save(tokenResponse(USER_A, 'r'));
    store.setItem(dashboardCacheKeyFor(USER_A), '{not json');
    expect(cache.load()).toBeNull();
    store.setItem(dashboardCacheKeyFor(USER_A), JSON.stringify({ savedAt: 1 }));
    expect(cache.load()).toBeNull();
    store.setItem(dashboardCacheKeyFor(USER_A), JSON.stringify({ savedAt: 1, data: { asOf: 'x' } }));
    expect(cache.load()).toBeNull();
  });

  it('forget removes only that user\'s entry', () => {
    session.save(tokenResponse(USER_A, 'r'));
    cache.save(view());
    store.setItem(dashboardCacheKeyFor(USER_B), '{}');
    cache.forget(USER_A);
    expect(Object.keys(store.data).filter((k) => k.startsWith('finpath.dashboard'))).toEqual([dashboardCacheKeyFor(USER_B)]);
  });
});
