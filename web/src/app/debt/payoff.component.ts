import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, map, of, switchMap, tap } from 'rxjs';
import type { EChartsOption } from 'echarts';
import { use } from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { AriaComponent, GridComponent, TooltipComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { errorMessage, shortDate } from '../plan/plan-format';
import { ThemeService } from '../theme.service';
import { ChartPalette, rollOffOption, timelineOption } from './payoff-chart-options';
import { PayoffApiService } from './payoff-api.service';
import { describeTimeline, payoffRows } from './payoff-format';
import { PayoffPlanView, PayoffStrategy, RollOffView } from './payoff.models';

use([BarChart, LineChart, GridComponent, TooltipComponent, AriaComponent, SVGRenderer]);

const inr = new PaiseInrPipe();

/** Reads the active theme's tokens so chart colours follow dark mode. */
export function readPalette(el: Element = document.documentElement): ChartPalette {
  const css = getComputedStyle(el);
  const v = (n: string, d: string) => css.getPropertyValue(n).trim() || d;
  return {
    text: v('--fp-text', '#1b1f24'),
    muted: v('--fp-text-muted', '#4a5563'),
    border: v('--fp-border', '#d3d9e0'),
    bar: v('--fp-primary', '#0b5cad'),
    step: v('--fp-warn-bar', '#8a5a00'),
  };
}

@Component({
  selector: 'app-payoff',
  standalone: true,
  imports: [NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './payoff.component.html',
  styleUrl: './payoff.css',
})
export class PayoffComponent {
  private readonly api = inject(PayoffApiService);
  private readonly themeService = inject(ThemeService);
  readonly fmt = (p: number) => inr.transform(p);

  readonly strategies: { code: PayoffStrategy; name: string; hint: string }[] = [
    { code: 'avalanche', name: 'Avalanche', hint: 'highest rate first' },
    { code: 'snowball', name: 'Snowball', hint: 'smallest balance first' },
  ];
  readonly strategy = signal<PayoffStrategy>('avalanche');
  readonly plan = signal<PayoffPlanView | null>(null);
  readonly rollOff = signal<RollOffView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  readonly rows = computed(() => {
    const p = this.plan();
    return p ? payoffRows(p.order, this.rollOff()?.steps ?? [], shortDate) : [];
  });
  readonly description = computed(() => describeTimeline(this.rows(), this.plan()?.cycles ?? 0, this.fmt));
  readonly rollOffDescription = computed(() => {
    const steps = this.rollOff()?.steps ?? [];
    if (steps.length === 0) return 'No EMIs roll off yet.';
    return steps.map((s) => `Cycle ${s.cycleIndex + 1}: ${this.fmt(s.freedPaise)} freed, ${this.fmt(s.cumulativeFreedPaise)} in total`).join('. ') + '.';
  });
  readonly timelineOptions = computed<EChartsOption | null>(() => {
    this.themeService.theme();
    const p = this.plan();
    return p && p.order.length > 0 ? timelineOption(p.order, readPalette(), this.fmt) : null;
  });
  readonly rollOffOptions = computed<EChartsOption | null>(() => {
    this.themeService.theme();
    const s = this.rollOff()?.steps ?? [];
    return s.length > 0 ? rollOffOption(s, readPalette(), this.fmt) : null;
  });
  readonly strategyName = computed(() => this.strategies.find((s) => s.code === this.plan()?.strategy.toLowerCase())?.name ?? this.strategy());
  /** Height class scales with the number of loans, avoiding inline styles. */
  readonly chartSizeClass = computed(() => `h-${Math.min(10, Math.max(1, this.plan()?.order.length ?? 1))}`);

  private readonly requests = new Subject<PayoffStrategy>();

  constructor() {
    // switchMap drops any in-flight plan response superseded by a newer strategy choice.
    this.requests
      .pipe(
        tap(() => {
          this.loading.set(true);
          this.error.set('');
        }),
        switchMap((s) =>
          this.api.payoffPlan(s).pipe(
            map((p) => ({ p, e: null as HttpErrorResponse | null })),
            catchError((e: HttpErrorResponse) => of({ p: null, e })),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ p, e }) => {
        this.plan.set(p);
        this.loading.set(false);
        if (e) this.error.set(errorMessage(e.status, 'Loading the payoff plan'));
      });
    // Roll-off does not depend on strategy: fetch once. A failure only drops the optional column and redirect line.
    this.api.rollOff().pipe(catchError(() => of(null)), takeUntilDestroyed()).subscribe((r) => this.rollOff.set(r));
    this.requests.next(this.strategy());
  }

  choose(s: PayoffStrategy): void {
    if (s === this.strategy()) return;
    this.strategy.set(s);
    this.requests.next(s);
  }

  /** Retry the current strategy. */
  load(): void {
    this.requests.next(this.strategy());
  }
}
