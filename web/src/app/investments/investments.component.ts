import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import type { EChartsOption } from 'echarts';
import { use } from 'echarts/core';
import { PieChart } from 'echarts/charts';
import { AriaComponent, TooltipComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import { FpCardComponent } from '../ui/fp-card.component';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { formatExactPaise } from '../expense/amount-entry';
import { rupeesTextToPaiseOrZero } from '../goals/goal-format';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { errorMessage, paiseToRupeesText } from '../plan/plan-format';
import { ThemeService } from '../theme.service';
import { DonutPalette, allocationOption } from './allocation-chart-options';
import { HoldingApiService } from './holding-api.service';
import { allocationSlices, classLabel, describeAllocation, validName } from './holding-format';
import { ASSET_CLASSES, AssetClass, HoldingAggregateView, HoldingView, NAME_MAX, NAME_WARNING } from './holding.models';

use([PieChart, TooltipComponent, AriaComponent, SVGRenderer]);

const inr = new PaiseInrPipe();

const LIGHT = ['#0b5cad', '#8a5a00', '#1a6b30', '#7a3e9d', '#b42318', '#00695c', '#5d4037'];
const DARK = ['#7db7f5', '#e0a526', '#6fcf8a', '#c79cf0', '#ff7b72', '#4fd1c0', '#d7b59e'];

export function readDonutPalette(dark: boolean, el: Element = document.documentElement): DonutPalette {
  const css = getComputedStyle(el);
  const v = (n: string, d: string) => css.getPropertyValue(n).trim() || d;
  return { text: v('--fp-text', '#1b1f24'), border: v('--fp-bg', '#ffffff'), slices: dark ? DARK : LIGHT };
}

@Component({
  selector: 'app-investments',
  standalone: true,
  imports: [PaiseInrPipe, NgxEchartsDirective, FpCardComponent, FpPageHeaderComponent, FpProfileLinkComponent],
  providers: [provideEchartsCore({ echarts })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './investments.component.html',
  styleUrl: './investments.css',
})
export class InvestmentsComponent implements OnInit {
  private readonly api = inject(HoldingApiService);
  private readonly theme = inject(ThemeService);

  /** One EPF row only (OQ-1): hidden while another EPF holding exists, so the balance is never double-counted. */
  readonly classes = computed(() =>
    this.holdings().some((h) => h.assetClass === 'epf' && h.id !== this.editingId())
      ? ASSET_CLASSES.filter((c) => c.code !== 'epf')
      : ASSET_CLASSES,
  );
  readonly nameMax = NAME_MAX;
  readonly nameWarning = NAME_WARNING;
  readonly exact = formatExactPaise;
  readonly label = classLabel;
  readonly fmt = (p: number) => inr.transform(p);

  readonly holdings = signal<HoldingView[]>([]);
  readonly aggregate = signal<HoldingAggregateView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly message = signal('');

  readonly formOpen = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly assetClass = signal<AssetClass>('mf');
  readonly name = signal('');
  readonly investedText = signal('');
  readonly currentText = signal('');
  readonly monthlyText = signal('');
  readonly saving = signal(false);
  readonly confirmingId = signal<string | null>(null);
  readonly busy = signal(false);

  readonly investedPaise = computed(() => this.paise(this.investedText()));
  readonly currentPaise = computed(() => this.paise(this.currentText()));
  readonly monthlyPaise = computed(() => this.paise(this.monthlyText()));
  readonly canSave = computed(
    () => validName(this.name()) && this.investedPaise() !== null && this.currentPaise() !== null && this.monthlyPaise() !== null && !this.saving(),
  );

  readonly slices = computed(() => {
    const a = this.aggregate();
    return a ? allocationSlices(a.classes, a.total.currentValuePaise) : [];
  });
  readonly description = computed(() => {
    const a = this.aggregate();
    return describeAllocation(this.slices(), a ? this.fmt(a.total.currentValuePaise) : '', this.fmt);
  });
  readonly chartOptions = computed<EChartsOption | null>(() => {
    const dark = this.theme.theme() === 'dark';
    return this.slices().length > 0 ? allocationOption(this.slices(), readDonutPalette(dark), this.fmt) : null;
  });

  shareOf(c: string): string {
    const s = this.slices().find((x) => x.assetClass === c);
    return s ? `${s.percent}%` : '0%';
  }

  /** Blank means 0 (every amount here is optional); anything malformed is null and blocks Save. */
  private paise(text: string): number | null {
    return text.trim() === '' ? 0 : rupeesTextToPaiseOrZero(text);
  }

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set('');
    forkJoin({ holdings: this.api.list(), aggregate: this.api.aggregate() }).subscribe({
      next: ({ holdings, aggregate }) => {
        this.holdings.set(holdings);
        this.aggregate.set(aggregate);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Loading investments'),
    });
  }

  openAdd(): void {
    this.editingId.set(null);
    this.assetClass.set('mf');
    this.name.set('');
    this.investedText.set('');
    this.currentText.set('');
    this.monthlyText.set('');
    this.open();
  }

  openEdit(h: HoldingView): void {
    const t = (p: number) => (p === 0 ? '' : paiseToRupeesText(p));
    this.editingId.set(h.id);
    this.assetClass.set(h.assetClass);
    this.name.set(h.name);
    this.investedText.set(t(h.investedPaise));
    this.currentText.set(t(h.currentValuePaise));
    this.monthlyText.set(t(h.monthlyContributionPaise));
    this.open();
  }

  private open(): void {
    this.formOpen.set(true);
    this.message.set('');
    this.confirmingId.set(null);
  }

  cancel(): void {
    this.formOpen.set(false);
  }

  on(ref: { set(v: string): void }, event: Event): void {
    ref.set((event.target as HTMLInputElement).value);
  }

  save(): void {
    const investedPaise = this.investedPaise();
    const currentValuePaise = this.currentPaise();
    const monthlyContributionPaise = this.monthlyPaise();
    if (investedPaise === null || currentValuePaise === null || monthlyContributionPaise === null || !this.canSave()) return;
    const body = { assetClass: this.assetClass(), name: this.name().trim(), investedPaise, currentValuePaise, monthlyContributionPaise };
    this.saving.set(true);
    this.error.set('');
    const id = this.editingId();
    (id ? this.api.replace(id, body) : this.api.create(body)).subscribe({
      next: () => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.message.set('Holding saved.');
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Saving the holding'),
    });
  }

  askRemove(h: HoldingView): void {
    this.confirmingId.set(h.id);
  }

  keep(): void {
    this.confirmingId.set(null);
  }

  remove(h: HoldingView): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.api.remove(h.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.confirmingId.set(null);
        this.message.set('Holding removed.');
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Removing the holding'),
    });
  }

  private fail(e: HttpErrorResponse, action: string): void {
    this.loading.set(false);
    this.saving.set(false);
    this.busy.set(false);
    this.confirmingId.set(null);
    this.error.set(errorMessage(e.status, action));
  }
}
