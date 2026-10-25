import { Injectable, inject } from '@angular/core';
import { AUTH_STORAGE, AuthSessionStore, KeyValueStorage } from '../auth/session.store';
import { CachedDashboard, DashboardView } from './dashboard.models';

export const DASHBOARD_CACHE_KEY = 'finpath.dashboard.v1';

const isObj = (x: unknown): boolean => typeof x === 'object' && x !== null;

export const dashboardCacheKeyFor = (userId: string): string => `${DASHBOARD_CACHE_KEY}.${userId}`;

/**
 * Last good dashboard per user, for offline display (T79). The API sends `no-store` and the service worker never
 * caches /dashboard, so this is the one copy; it is keyed by user id and removed on sign-out (LocalDataService).
 */
@Injectable({ providedIn: 'root' })
export class DashboardCacheService {
  private readonly storage: KeyValueStorage | null = inject(AUTH_STORAGE);
  private readonly session = inject(AuthSessionStore);

  load(): CachedDashboard | null {
    const key = this.key();
    if (!key || !this.storage) return null;
    try {
      const raw = this.storage.getItem(key);
      if (!raw) return null;
      const o = JSON.parse(raw) as CachedDashboard;
      return o && typeof o.savedAt === 'number' && o.data && typeof o.data.asOf === 'string' && isObj(o.data.cycle) && isObj(o.data.budget) ? o : null;
    } catch {
      return null;
    }
  }

  save(data: DashboardView, now: number = Date.now()): void {
    const key = this.key();
    if (!key || !this.storage) return;
    try {
      this.storage.setItem(key, JSON.stringify({ data, savedAt: now } satisfies CachedDashboard));
    } catch {
      /* quota or blocked storage: the live view still works */
    }
  }

  forget(userId: string): void {
    try {
      this.storage?.removeItem(dashboardCacheKeyFor(userId));
    } catch {
      /* ignore */
    }
  }

  private key(): string | null {
    const id = this.session.userId();
    return id ? dashboardCacheKeyFor(id) : null;
  }
}
