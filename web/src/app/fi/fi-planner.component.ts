import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import type { EChartsOption } from 'echarts';
import { use } from 'echarts/core';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { errorMessage, rupeesTextToPaise } from '../plan/plan-format';
import { ThemeService } from '../theme.service';
import { FiPalette, fiOption } from './fi-chart-options';
import { FiApiService } from './fi-api.service';
import { describeFi, fiRows, fractionToPercent } from './fi-format';
import { FiPlanRequest, FiPlanView } from './fi.models';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';

use([LineChart, GridComponent, TooltipComponent, LegendComponent, AriaComponent, SVGRenderer]);

const inr = new PaiseInrPipe();

export function readFiPalette(el: Element = document.documentElement): FiPalette {
  const css = getComputedStyle(el);
  const v = (n: string) => css.getPropertyValue(n).trim();
  return {
    text: v('--fp-text'),
    muted: v('--fp-text-muted'),
    border: v('--fp-border'),
    line: v('--fp-primary'),
    target: v('--fp-warn-bar'),
  };
}

@Component({
  selector: 'app-fi-planner',
  standalone: true,
  imports: [FpPageHeaderComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fi-planner.component.html',
  styleUrl: './fi-planner.css',
})
export class FiPlannerComponent {
  private readonly api = inject(FiApiService);
  private readonly themeService = inject(ThemeService);
  readonly fmt = (p: number) => inr.transform(p);
  readonly pct = fractionToPercent;

  /** Step-up choices are API-ready fraction strings; nothing is converted client-side. */
  readonly stepUps: { value: string; label: string }[] = [
    { value: '0', label: 'None' },
    { value: '0.05', label: '5% a year' },
    { value: '0.10', label: '10% a year' },
  ];

  readonly expense = signal('');
  readonly passive = signal('');
  readonly opening = signal('');
  readonly sip = signal('');
  readonly stepUp = signal('0');
  readonly years = signal('');

  readonly plan = signal<FiPlanView | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly formError = signal('');

  readonly rows = computed(() => fiRows(this.plan()?.bands ?? []));
  readonly description = computed(() => {
    const p = this.plan();
    return p ? describeFi(this.rows(), p.netCorpusPaise, p.yearsToFi, this.fmt) : '';
  });
  readonly options = computed<EChartsOption | null>(() => {
    this.themeService.theme();
    const p = this.plan();
    return p && this.rows().length > 0 ? fiOption(this.rows(), p.netCorpusPaise, readFiPalette(), this.fmt) : null;
  });

  set(field: 'expense' | 'passive' | 'opening' | 'sip' | 'stepUp' | 'years', ev: Event): void {
    this[field].set((ev.target as HTMLInputElement | HTMLSelectElement).value);
  }

  /** Validates typed text into API-ready paise (string handling only) and asks the API for the plan. */
  submit(): void {
    const req = this.buildRequest();
    if (!req) return;
    this.loading.set(true);
    this.error.set('');
    this.api.plan(req).subscribe({
      next: (p) => {
        this.plan.set(p);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.plan.set(null);
        this.loading.set(false);
        this.error.set(errorMessage(e.status, 'Calculating the plan'));
      },
    });
  }

  private buildRequest(): FiPlanRequest | null {
    this.formError.set('');
    const expense = rupeesTextToPaise(this.expense());
    const yearsText = this.years().trim();
    const years = /^\d{1,2}$/.test(yearsText) ? parseInt(yearsText, 10) : 0;
    if (expense === null || years < 1 || years > 60) {
      this.formError.set('Enter your monthly expense in rupees and years to FI between 1 and 60.');
      return null;
    }
    const optional = [this.passive(), this.opening(), this.sip()].map((t) => (t.trim() === '' || t.trim() === '0' ? undefined : rupeesTextToPaise(t)));
    if (optional.some((o) => o === null)) {
      this.formError.set('Optional amounts must be rupees such as 25000 or 12.50.');
      return null;
    }
    const [passive, opening, sip] = optional as (number | undefined)[];
    return {
      todayMonthlyExpensePaise: expense,
      todayMonthlyPassivePaise: passive,
      openingBalancePaise: opening,
      monthlySipPaise: sip,
      stepUp: this.stepUp(),
      yearsToFi: years,
    };
  }
}
