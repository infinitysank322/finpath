import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import type { EChartsOption } from 'echarts';
import { use } from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { errorMessage } from '../plan/plan-format';
import { ThemeService } from '../theme.service';
import { LoanInterestApiService } from './loan-interest-api.service';
import { InterestPalette, interestOption } from './loan-interest-chart-options';
import { describeInterest, exactInr, instalmentTotals } from './loan-interest-format';
import { LoanDetailView } from './loan-interest.models';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';

use([LineChart, GridComponent, TooltipComponent, LegendComponent, AriaComponent, SVGRenderer]);

const inr = new PaiseInrPipe();

/** Reads the active theme's tokens so chart colours follow dark mode. */
export function readInterestPalette(el: Element = document.documentElement): InterestPalette {
  const css = getComputedStyle(el);
  const v = (n: string) => css.getPropertyValue(n).trim();
  return {
    text: v('--fp-text'),
    muted: v('--fp-text-muted'),
    border: v('--fp-border'),
    principal: v('--fp-primary'),
    interest: v('--fp-warn-bar'),
  };
}

@Component({
  selector: 'app-loan-interest',
  standalone: true,
  imports: [FpPageHeaderComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './loan-interest.component.html',
  styleUrl: './loan-interest.css',
})
export class LoanInterestComponent {
  private readonly api = inject(LoanInterestApiService);
  private readonly themeService = inject(ThemeService);
  private readonly route = inject(ActivatedRoute);
  /** Axis labels stay compact; every table and spoken figure is exact to the paise. */
  readonly fmt = exactInr;
  readonly axisFmt = (p: number) => inr.transform(p);

  readonly loan = signal<LoanDetailView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  readonly totals = computed(() => instalmentTotals(this.loan()?.instalments ?? []));
  readonly description = computed(() => describeInterest(this.loan()?.instalments ?? [], this.totals(), this.fmt));
  readonly chartOptions = computed<EChartsOption | null>(() => {
    this.themeService.theme();
    const rows = this.loan()?.instalments ?? [];
    return rows.length > 0 ? interestOption(rows, readInterestPalette(), this.axisFmt, this.fmt) : null;
  });

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((m) => {
      this.id = m.get('id') ?? '';
      this.load();
    });
  }

  private id = '';

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.api.detail(this.id).subscribe({
      next: (l) => {
        this.loan.set(l);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.loan.set(null);
        this.loading.set(false);
        this.error.set(errorMessage(e.status, 'Loading the loan'));
      },
    });
  }
}
