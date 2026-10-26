import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import type { EChartsOption } from 'echarts';
import * as echarts from 'echarts/core';
import { use } from 'echarts/core';
import { BarChart, PieChart } from 'echarts/charts';
import { AriaComponent, DatasetComponent, GridComponent, TooltipComponent, TransformComponent } from 'echarts/components';
import { LabelLayout } from 'echarts/features';
import { SVGRenderer } from 'echarts/renderers';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { errorMessage } from '../plan/plan-format';
import { ThemeService } from '../theme.service';
import { FpCardComponent } from '../ui/fp-card.component';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';
import { SpendingApiService } from './spending-api.service';
import { comparisonOption, donutOption, SpendingPalette } from './spending-chart-options';
import {
  baselineLabel, changeText, comparisonRows, cycleLabel, describeComparison, describeDonut, donutRows, tagRows,
} from './spending-format';
import { CategoryBreakdown, SpendingComparison, TagSummary } from './spending.models';

use([PieChart, BarChart, GridComponent, TooltipComponent, AriaComponent, DatasetComponent, TransformComponent, LabelLayout, SVGRenderer]);

const inr = new PaiseInrPipe();

/** Reads the active theme's tokens so chart colours follow dark mode. */
export function readSpendingPalette(el: Element = document.documentElement): SpendingPalette {
  const css = getComputedStyle(el);
  const v = (n: string) => css.getPropertyValue(n).trim();
  return {
    text: v('--fp-text'),
    muted: v('--fp-text-muted'),
    border: v('--fp-border'),
    bg: v('--fp-surface') || v('--fp-bg'),
    slices: [v('--fp-accent-edge'), v('--fp-warn-bar'), v('--fp-breach-bar'), v('--fp-series-neutral'), v('--fp-text-muted'), v('--fp-primary-text')],
    current: v('--fp-primary'),
    average: v('--fp-series-neutral'),
  };
}

@Component({
  selector: 'app-spending',
  standalone: true,
  imports: [FpCardComponent, FpPageHeaderComponent, FpProfileLinkComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './spending.component.html',
  styleUrl: './spending.css',
})
export class SpendingComponent implements OnInit {
  private readonly api = inject(SpendingApiService);
  private readonly themeService = inject(ThemeService);
  readonly fmt = (p: number) => inr.transform(p);
  readonly changeText = (d: number) => changeText(d, this.fmt);

  readonly tags = signal<TagSummary | null>(null);
  readonly categories = signal<CategoryBreakdown | null>(null);
  readonly comparison = signal<SpendingComparison | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly comparisonError = signal('');
  private pending = 0;

  readonly tagRows = computed(() => (this.tags() ? tagRows(this.tags()!) : []));
  readonly cycle = computed(() => {
    const c = this.categories();
    return c ? cycleLabel(c.cycleStart, c.cycleEndExclusive) : '';
  });
  readonly donutRows = computed(() => donutRows(this.categories()?.lines ?? []));
  readonly donutDescription = computed(() => describeDonut(this.donutRows(), this.fmt(this.categories()?.totalPaise ?? 0), this.cycle()));
  readonly donutOptions = computed<EChartsOption | null>(() => {
    this.themeService.theme();
    return this.donutRows().length > 0 ? donutOption(this.donutRows(), readSpendingPalette(), this.fmt) : null;
  });

  readonly currentLabel = computed(() => {
    const c = this.comparison();
    return c ? cycleLabel(c.cycleStart, c.cycleEndExclusive) : '';
  });
  readonly baseline = computed(() => baselineLabel(this.comparison()?.baselineCycles ?? []));
  readonly baselineCount = computed(() => this.comparison()?.baselineCycles.length ?? 0);
  readonly compRows = computed(() => comparisonRows(this.comparison()?.lines ?? []));
  readonly compDescription = computed(() => describeComparison(this.compRows(), this.currentLabel(), this.baseline(), this.fmt));
  readonly compOptions = computed<EChartsOption | null>(() => {
    this.themeService.theme();
    return this.compRows().length > 0 ? comparisonOption(this.compRows(), readSpendingPalette(), this.fmt) : null;
  });
  /** Bar chart height scales with the number of categories: two bars each, so a step per category and a half. */
  readonly compBars = computed(() => Math.min(16, Math.max(1, this.compRows().length)) * 1.5);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.comparisonError.set('');
    this.pending = 3;
    this.api.tagSummary().subscribe({ next: (t) => this.tags.set(t), error: () => this.tags.set(null), complete: () => this.settle() });
    this.api.categories().subscribe({
      next: (c) => this.categories.set(c),
      error: (e: HttpErrorResponse) => {
        this.categories.set(null);
        this.error.set(e.status === 404 ? 'Set up your plan cycle first.' : errorMessage(e.status, 'Loading spending'));
        this.settle();
      },
      complete: () => this.settle(),
    });
    this.api.comparison().subscribe({
      next: (c) => this.comparison.set(c),
      error: (e: HttpErrorResponse) => {
        this.comparison.set(null);
        this.comparisonError.set(errorMessage(e.status, 'Loading the comparison'));
        this.settle();
      },
      complete: () => this.settle(),
    });
  }

  private settle(): void {
    this.pending = Math.max(0, this.pending - 1);
    if (this.pending === 0) this.loading.set(false);
  }
}
