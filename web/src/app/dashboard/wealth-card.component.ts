import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import type { EChartsOption } from 'echarts';
import { use } from 'echarts/core';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { AriaComponent, GridComponent, TooltipComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import { readFiPalette } from '../fi/fi-planner.component';
import { bandLabel, fractionToPercent } from '../fi/fi-format';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { shortDate } from '../plan/plan-format';
import { ThemeService } from '../theme.service';
import { FpCardComponent } from '../ui/fp-card.component';
import { DashboardView } from './dashboard.models';
import { netWorthOption } from './wealth-chart-options';

use([LineChart, GridComponent, TooltipComponent, AriaComponent, SVGRenderer]);

const inr = new PaiseInrPipe();

/**
 * Home wealth section (T76): the net-worth line and the FI countdown card. Every figure is read from GET /dashboard;
 * the series is the API's snapshots and nothing is recomputed here. The chart has an aria description and a table.
 */
@Component({
  selector: 'app-wealth-card',
  standalone: true,
  imports: [PaiseInrPipe, FpCardComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './wealth-card.component.html',
  styles: `
    .chart { width: 100%; height: 14rem; }
    .tbl { width: 100%; border-collapse: collapse; text-align: left; }
    .tbl th, .tbl td { padding: var(--fp-space-1) var(--fp-space-2); }
    .tbl td:last-child, .tbl th:last-child { text-align: right; }
    .fi-date { font-size: var(--fp-fs-xl); font-weight: 700; margin: 0; }
    .assume { margin: 0; padding-left: 1.2rem; }
  `,
})
export class WealthCardComponent {
  private readonly themeService = inject(ThemeService);
  readonly view = input.required<DashboardView>();
  readonly fmt = (p: number) => inr.transform(p);
  readonly pct = fractionToPercent;
  readonly band = bandLabel;
  readonly date = shortDate;

  readonly nw = computed(() => this.view().netWorth ?? null);
  readonly series = computed(() => this.nw()?.series ?? []);
  readonly fi = computed(() => this.view().fi ?? null);
  readonly fiYearsText = (y: number | null) => (y === null ? 'not reached in the planning horizon' : `${y} yrs`);
  readonly deltaText = computed(() => {
    const d = this.fi()?.deltaYearsVsLastCycle;
    if (d == null) return 'Change vs last cycle: not available yet';
    return d === 0 ? 'No change vs last cycle' : `${d < 0 ? 'Closer' : 'Further'} by ${Math.abs(d)} yr${Math.abs(d) === 1 ? '' : 's'} vs last cycle`;
  });
  readonly description = computed(() => {
    const s = this.series();
    if (s.length === 0) return 'Net worth line: no cycle snapshots yet';
    return `Net worth over ${s.length} cycle${s.length === 1 ? '' : 's'}: ` +
      s.map((p) => `${shortDate(p.cycleStart)} ${this.fmt(p.netWorthPaise)}`).join(', ') + '.';
  });
  readonly options = computed<EChartsOption | null>(() => {
    this.themeService.theme();
    const s = this.series();
    return s.length > 0 ? netWorthOption(s, shortDate, readFiPalette(), this.fmt) : null;
  });
}
