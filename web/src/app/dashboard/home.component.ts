import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { DashboardApiService } from './dashboard-api.service';
import { DashboardCacheService } from './dashboard-cache.service';
import { DashboardView } from './dashboard.models';
import { ageLabel } from './staleness';

/** Status 0 = fetch failed; 504 = what the Angular service worker answers when the network is down
 * (a genuine gateway 504 is treated the same: either way the server could not be reached). */
const isUnreachable = (e: unknown): boolean => e instanceof HttpErrorResponse && (e.status === 0 || e.status === 504);

/**
 * Home shell for T79: shows the live dashboard, or the last cached one with a staleness label when the network is
 * unreachable. T75/T76 replace the body with the ring and cards; the load/fallback contract stays.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [PaiseInrPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Home</h1>
    @if (stale(); as s) {
      <p role="status" class="muted" data-f="stale">
        Cannot reach FinPath right now. Showing your dashboard as of {{ s.asOf }}, saved {{ s.age }}.
      </p>
    }
    @if (view(); as v) {
      <p data-f="days-left">{{ v.cycle.daysLeft }} days left in this cycle</p>
      @if (v.budget.safeToSpendPerDayPaise !== null) {
        <p data-f="safe">Safe to spend per day: {{ v.budget.safeToSpendPerDayPaise | paiseInr }}</p>
      }
    } @else if (failed()) {
      <p role="alert" data-f="failed">Could not load your dashboard. Check your connection and try again.</p>
      <button type="button" (click)="load()">Retry</button>
    } @else {
      <p class="muted" aria-busy="true" data-f="loading">Loading your dashboard</p>
    }
  `,
  styles: [`h1 { margin: 0 0 .5rem; font-size: 1.5rem; }`],
})
export class HomeComponent implements OnInit, OnDestroy {
  private readonly api = inject(DashboardApiService);
  private readonly cache = inject(DashboardCacheService);

  readonly view = signal<DashboardView | null>(null);
  readonly stale = signal<{ asOf: string; age: string } | null>(null);
  readonly failed = signal(false);

  ngOnInit(): void {
    this.load();
  }

  private inflight?: Subscription;

  ngOnDestroy(): void {
    this.inflight?.unsubscribe();
  }

  load(): void {
    this.failed.set(false);
    this.inflight?.unsubscribe(); // a newer request wins; an older one must not overwrite it
    this.inflight = this.api.get().subscribe({
      next: (d) => {
        this.cache.save(d);
        this.stale.set(null);
        this.view.set(d);
      },
      error: (e: unknown) => {
        const cached = this.cache.load();
        // Only an unreachable server falls back; an API error must not be dressed up as offline data.
        if (cached && isUnreachable(e)) {
          this.view.set(cached.data);
          this.stale.set({ asOf: cached.data.asOf, age: ageLabel(cached.savedAt, Date.now()) });
        } else if (!this.view()) {
          this.failed.set(true);
        }
      },
    });
  }
}
