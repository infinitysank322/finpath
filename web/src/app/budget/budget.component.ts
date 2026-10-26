import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { formatExactPaise, toPaise } from '../expense/amount-entry';
import { OUT_CATEGORIES } from '../expense/expense.models';
import { errorMessage, paiseToRupeesText, shortDate } from '../plan/plan-format';
import { BudgetApiService } from './budget-api.service';
import { barWidthText, remainingText, fractionToPercentText } from './budget-format';
import { BudgetEvent, BudgetLevel, BudgetLine, BudgetStatus } from './budget.models';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';

@Component({
  selector: 'app-budget',
  standalone: true,
  imports: [FpPageHeaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './budget.component.html',
  styleUrl: './budget.css',
})
export class BudgetComponent implements OnInit {
  private readonly api = inject(BudgetApiService);
  private readonly injector = inject(Injector);
  private opener: HTMLElement | null = null;

  readonly exact = formatExactPaise;
  readonly shortDate = shortDate;
  readonly percent = fractionToPercentText;
  readonly barWidth = (f: string | null) => barWidthText(fractionToPercentText(f));
  readonly remainingText = remainingText;
  readonly categoryName = (code: string) => OUT_CATEGORIES.find((c) => c.code === code)?.name ?? code;
  readonly categories = OUT_CATEGORIES;

  readonly status = signal<BudgetStatus | null>(null);
  readonly events = signal<BudgetEvent[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly message = signal('');
  readonly sheetError = signal('');
  readonly evaluateFailed = signal(false);

  readonly sheetOpen = signal(false);
  readonly sheetCategory = signal('');
  readonly lockedCategory = signal(false);
  readonly amountText = signal('');
  readonly saving = signal(false);
  readonly confirmingCode = signal<string | null>(null);

  /** Whole rupees with up to two decimals; zero is a valid budget (T40 section 7.4). */
  readonly amountValid = computed(() => /^\d{1,9}(\.\d{1,2})?$/.test(this.amountText().trim().replace(/,/g, '')));
  readonly canSave = computed(() => this.sheetCategory() !== '' && this.amountValid() && !this.saving());
  /** Persistent banners come from the status levels, so they survive a reload; events are only a one-time notice. */
  readonly alertLines = computed(() => (this.status()?.lines ?? []).filter((l) => l.level === 'WARN' || l.level === 'BREACH'));
  readonly unbudgeted = computed(() => {
    const used = new Set((this.status()?.lines ?? []).map((l) => l.categoryCode));
    return OUT_CATEGORIES.filter((c) => !used.has(c.code));
  });

  ngOnInit(): void {
    // Evaluate once on open so each threshold event is announced once; then read the status.
    this.api.evaluate().subscribe({
      next: (r) => {
        this.events.set(r.events);
        this.loadStatus();
      },
      error: () => {
        this.evaluateFailed.set(true); // best effort: say so, and still show the lines
        this.loadStatus();
      },
    });
  }

  loadStatus(): void {
    this.loading.set(true);
    this.api.status().subscribe({
      next: (s) => {
        this.status.set(s);
        this.error.set('');
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.status.set(null);
        this.fail(e, 'Loading budgets');
        if (e.status === 404) this.error.set('Set up your plan cycle first.');
      },
    });
  }

  levelText(level: BudgetLevel): string {
    return level === 'BREACH' ? 'Over budget' : level === 'WARN' ? 'Nearly used up' : 'On track';
  }

  eventText(e: BudgetEvent): string {
    const n = this.categoryName(e.categoryCode);
    return e.level === 'BREACH' ? `${n} has reached or passed its budget.` : `${n} is close to its budget.`;
  }

  lineAlertText(l: BudgetLine): string {
    return this.eventText({ categoryCode: l.categoryCode, level: l.level });
  }

  openEdit(line: BudgetLine): void {
    this.sheetCategory.set(line.categoryCode);
    this.lockedCategory.set(true);
    this.amountText.set(paiseToRupeesText(line.budgetPaise));
    this.openSheet();
  }

  openAdd(): void {
    this.sheetCategory.set(this.unbudgeted()[0]?.code ?? '');
    this.lockedCategory.set(false);
    this.amountText.set('');
    this.openSheet();
  }

  closeSheet(): void {
    this.sheetOpen.set(false);
    this.sheetError.set('');
    const el = this.opener;
    this.opener = null;
    if (el) afterNextRender(() => el.focus(), { injector: this.injector });
  }

  on(ref: { set(v: string): void }, event: Event): void {
    ref.set((event.target as HTMLInputElement).value);
  }

  save(): void {
    if (!this.canSave()) return;
    const budgetPaise = toPaise(this.amountText().trim().replace(/,/g, ''));
    this.saving.set(true);
    this.sheetError.set('');
    this.api.put(this.sheetCategory(), budgetPaise).subscribe({
      next: () => {
        this.saving.set(false);
        this.closeSheet();
        this.message.set('Budget saved.');
        this.loadStatus();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Saving the budget'),
    });
  }

  askRemove(code: string): void {
    this.confirmingCode.set(code);
  }

  keep(): void {
    this.confirmingCode.set(null);
  }

  remove(code: string): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.api.delete(code).subscribe({
      next: () => {
        this.saving.set(false);
        this.confirmingCode.set(null);
        this.message.set('Budget removed.');
        this.loadStatus();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Removing the budget'),
    });
  }

  private openSheet(): void {
    this.message.set('');
    this.sheetError.set('');
    this.confirmingCode.set(null);
    this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.sheetOpen.set(true);
    afterNextRender(
      () => (document.querySelector('app-budget .sheet select:not([disabled]), app-budget .sheet input') as HTMLElement | null)?.focus(),
      { injector: this.injector },
    );
  }

  private fail(e: HttpErrorResponse, action: string): void {
    this.loading.set(false);
    this.saving.set(false);
    this.confirmingCode.set(null);
    const text = errorMessage(e.status, action);
    if (this.sheetOpen()) this.sheetError.set(text);
    else this.error.set(text);
  }
}
