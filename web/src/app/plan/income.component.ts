import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { formatExactPaise } from '../expense/amount-entry';
import { todayIst } from '../expense/add-expense.component';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { PlanApiService } from './plan-api.service';
import { errorMessage, paiseToRupeesText, rupeesTextToPaise, shortDate } from './plan-format';
import { CycleTotalView, INCOME_FREQUENCIES, INCOME_TYPES, IncomeFrequency, IncomeType, IncomeView } from './plan.models';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';

@Component({
  selector: 'app-income',
  standalone: true,
  imports: [FpPageHeaderComponent, PaiseInrPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './income.component.html',
  styleUrl: './plan.css',
})
export class IncomeComponent implements OnInit {
  private readonly api = inject(PlanApiService);

  readonly types = INCOME_TYPES;
  readonly frequencies = INCOME_FREQUENCIES;
  readonly exact = formatExactPaise;
  readonly shortDate = shortDate;
  readonly typeLabel = (c: IncomeType) => INCOME_TYPES.find((t) => t.code === c)?.label ?? c;
  readonly freqLabel = (c: IncomeFrequency) => INCOME_FREQUENCIES.find((t) => t.code === c)?.label ?? c;

  readonly items = signal<IncomeView[]>([]);
  readonly total = signal<CycleTotalView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly message = signal('');

  readonly formOpen = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly type = signal<IncomeType>('SALARY');
  readonly frequency = signal<IncomeFrequency>('MONTHLY');
  readonly amountText = signal('');
  readonly startDate = signal(todayIst());
  readonly endDate = signal('');
  readonly saving = signal(false);

  readonly amountPaise = computed(() => rupeesTextToPaise(this.amountText()));
  readonly confirmingId = signal<string | null>(null);
  readonly busy = signal(false);
  /** ISO dates compare correctly as strings; a one-off has no end date. */
  readonly dateError = computed(() =>
    this.frequency() === 'MONTHLY' && this.endDate() !== '' && this.endDate() < this.startDate()
      ? 'The end date must not be before the start date.'
      : '',
  );
  readonly canSave = computed(
    () =>
      this.amountPaise() !== null &&
      /^\d{4}-\d{2}-\d{2}$/.test(this.startDate()) &&
      this.dateError() === '' &&
      !this.saving(),
  );

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set('');
    forkJoin({ rows: this.api.incomeList(), total: this.api.incomeCycleTotal() }).subscribe({
      next: ({ rows, total }) => {
        this.items.set(rows);
        this.total.set(total);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.items.set([]);
        this.total.set(null); // never show a stale headline next to a failed list
        this.fail(e, 'Loading income');
      },
    });
  }

  openAdd(): void {
    this.editingId.set(null);
    this.type.set('SALARY');
    this.frequency.set('MONTHLY');
    this.amountText.set('');
    this.startDate.set(todayIst());
    this.endDate.set('');
    this.formOpen.set(true);
    this.message.set('');
    this.confirmingId.set(null);
  }

  openEdit(row: IncomeView): void {
    this.editingId.set(row.id);
    this.type.set(row.type);
    this.frequency.set(row.frequency);
    this.amountText.set(paiseToRupeesText(row.amountPaise));
    this.startDate.set(row.startDate);
    this.endDate.set(row.endDate ?? '');
    this.formOpen.set(true);
    this.message.set('');
    this.confirmingId.set(null);
  }

  cancel(): void {
    this.formOpen.set(false);
  }

  setFrequency(f: IncomeFrequency): void {
    this.frequency.set(f);
    if (f === 'ONE_OFF') this.endDate.set(''); // the API rejects an end date on a one-off
  }

  on(signalRef: { set(v: string): void }, event: Event): void {
    signalRef.set((event.target as HTMLInputElement).value);
  }

  save(): void {
    const amountPaise = this.amountPaise();
    if (amountPaise === null || !this.canSave()) return;
    const body = {
      type: this.type(),
      frequency: this.frequency(),
      amountPaise,
      startDate: this.startDate(),
      endDate: this.frequency() === 'ONE_OFF' || this.endDate() === '' ? null : this.endDate(),
    };
    this.saving.set(true);
    this.error.set('');
    const id = this.editingId();
    (id ? this.api.incomeReplace(id, body) : this.api.incomeCreate(body)).subscribe({
      next: () => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.message.set('Income saved.');
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Saving income'),
    });
  }

  askRemove(row: IncomeView): void {
    this.confirmingId.set(row.id);
  }

  keep(): void {
    this.confirmingId.set(null);
  }

  remove(row: IncomeView): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.api.incomeDelete(row.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.confirmingId.set(null);
        this.message.set('Income removed.');
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Removing income'),
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
