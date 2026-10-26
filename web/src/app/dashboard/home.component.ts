import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnDestroy, computed, OnInit, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { DashboardApiService } from './dashboard-api.service';
import { DashboardCacheService } from './dashboard-cache.service';
import { DashboardView } from './dashboard.models';
import { ratioPercentText } from '../debt/guard-format';
import { FpCardComponent } from '../ui/fp-card.component';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';
import { ageLabel } from './staleness';
import { WealthCardComponent } from './wealth-card.component';

/** Status 0 = fetch failed; 504 = what the Angular service worker answers when the network is down
 * (a genuine gateway 504 is treated the same: either way the server could not be reached). */
const inr = new PaiseInrPipe();
const isUnreachable = (e: unknown): boolean => e instanceof HttpErrorResponse && (e.status === 0 || e.status === 504);

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
/** "2026-10-25" -> "25 Oct": formatting of an API date, no date arithmetic. */
const shortDate = (iso: string): string => {
  const [, m, d] = iso.split('-');
  return `${Number(d)} ${MONTHS[Number(m) - 1] ?? ''}`.trim();
};

/**
 * Home (T75, on T79's load/offline contract): the cycle-progress ring. Every figure is read from GET /dashboard; the
 * ring angle is the API's `usedFraction` and nothing is computed here. The ring has an aria-label and visible text.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [PaiseInrPipe, FpCardComponent, FpPageHeaderComponent, FpProfileLinkComponent, WealthCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.component.html',
  styleUrl: './home.component.css',
})
export class HomeComponent implements OnInit, OnDestroy {
  private readonly api = inject(DashboardApiService);
  private readonly cache = inject(DashboardCacheService);

  readonly view = signal<DashboardView | null>(null);
  readonly stale = signal<{ asOf: string; age: string } | null>(null);
  readonly failed = signal(false);

  readonly cycleStart = computed(() => shortDate(this.view()?.cycle.start ?? ''));
  readonly cycleNext = computed(() => shortDate(this.view()?.cycle.endExclusive ?? ''));
  /** Used share as the API's own decimal string, as a percentage caption. */
  readonly usedText = computed(() => {
    const f = this.view()?.budget.usedFraction;
    return f == null ? '' : ratioPercentText(f);
  });
  /** SVG dash for pathLength=100: the API fraction scaled to the ring, clamped to one full turn (geometry only). */
  readonly dash = computed(() => {
    const n = Number(this.view()?.budget.usedFraction ?? 0);
    const pct = Number.isFinite(n) ? Math.min(100, Math.max(0, n * 100)) : 0;
    return `${pct} 100`;
  });
  readonly levelWord = computed(() => {
    switch (this.view()?.budget.level) {
      case 'WARN': return 'Warning: close to your budget';
      case 'BREACH': return 'Over budget';
      default: return 'On track';
    }
  });
  readonly levelIcon = computed(() => (this.view()?.budget.level === 'WARN' ? '!' : this.view()?.budget.level === 'BREACH' ? '✕' : '✓'));
  readonly summary = computed(() => {
    const v = this.view();
    if (!v || v.budget.status !== 'OK') return 'Cycle spending ring: budget not set up';
    const b = v.budget;
    return `Cycle spending: ${this.usedText()} of budget used. Spent ${inr.transform(b.spentPaise)} of ${inr.transform(b.budgetPaise)}. ` +
      `${v.cycle.daysLeft} days left. Safe to spend ${inr.transform(b.safeToSpendPerDayPaise)} per day. ${this.levelWord()}.`;
  });

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
