import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { formatExactPaise } from '../expense/amount-entry';
import { todayIst } from '../expense/add-expense.component';
import { MODES, OUT_CATEGORIES, PaymentMode, SpendTag, TAGS } from '../expense/expense.models';
import { PlanApiService } from './plan-api.service';
import { errorMessage, paiseToRupeesText, rupeesTextToPaise, shortDate } from './plan-format';
import { MaterialiseResult, RECURRING_FREQUENCIES, RecurringFrequency, TemplateView } from './plan.models';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';

@Component({
  selector: 'app-recurring',
  standalone: true,
  imports: [FpPageHeaderComponent, FpProfileLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './recurring.component.html',
  styleUrl: './plan.css',
})
export class RecurringComponent implements OnInit {
  private readonly api = inject(PlanApiService);
  readonly categories = OUT_CATEGORIES;
  readonly modes = MODES;
  readonly tags = TAGS;
  readonly frequencies = RECURRING_FREQUENCIES;
  readonly exact = formatExactPaise;
  readonly shortDate = shortDate;
  readonly categoryName = (c: string) => this.categories.find((x) => x.code === c)?.name ?? c;
  readonly freq = (c: RecurringFrequency) => RECURRING_FREQUENCIES.find((x) => x.code === c);

  readonly items = signal<TemplateView[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly message = signal('');
  readonly posted = signal<MaterialiseResult | null>(null);
  readonly posting = signal(false);

  readonly formOpen = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly category = signal('UTILITIES');
  readonly frequency = signal<RecurringFrequency>('QUARTERLY');
  readonly mode = signal<PaymentMode>('UPI');
  readonly tag = signal<SpendTag | ''>('');
  readonly amountText = signal('');
  readonly startDate = signal(todayIst());
  readonly endDate = signal('');
  readonly saving = signal(false);

  readonly amountPaise = computed(() => rupeesTextToPaise(this.amountText()));
  readonly confirmingId = signal<string | null>(null);
  readonly busy = signal(false);
  readonly dateError = computed(() =>
    this.endDate() !== '' && this.endDate() < this.startDate() ? 'The end date must not be before the start date.' : '',
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
    this.api.templateList().subscribe({
      next: (rows) => {
        this.items.set(rows);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Loading bills'),
    });
  }

  openAdd(): void {
    this.editingId.set(null);
    this.category.set('UTILITIES');
    this.frequency.set('QUARTERLY');
    this.mode.set('UPI');
    this.tag.set('');
    this.amountText.set('');
    this.startDate.set(todayIst());
    this.endDate.set('');
    this.formOpen.set(true);
    this.message.set('');
    this.posted.set(null);
    this.confirmingId.set(null);
  }

  openEdit(r: TemplateView): void {
    this.editingId.set(r.id);
    this.category.set(r.categoryCode);
    this.frequency.set(r.frequency);
    this.mode.set(r.mode);
    this.tag.set(r.tag ?? '');
    this.amountText.set(paiseToRupeesText(r.amountPaise));
    this.startDate.set(r.startDate);
    this.endDate.set(r.endDate ?? '');
    this.formOpen.set(true);
    this.message.set('');
    this.posted.set(null);
    this.confirmingId.set(null);
  }

  cancel(): void {
    this.formOpen.set(false);
  }

  on(signalRef: { set(v: string): void }, event: Event): void {
    signalRef.set((event.target as HTMLInputElement).value);
  }

  save(): void {
    const amountPaise = this.amountPaise();
    if (amountPaise === null || !this.canSave()) return;
    const body = {
      categoryCode: this.category(),
      amountPaise,
      frequency: this.frequency(),
      mode: this.mode(),
      tag: this.tag() === '' ? null : (this.tag() as SpendTag),
      startDate: this.startDate(),
      endDate: this.endDate() === '' ? null : this.endDate(),
    };
    this.saving.set(true);
    this.error.set('');
    this.posted.set(null);
    const id = this.editingId();
    (id ? this.api.templateReplace(id, body) : this.api.templateCreate(body)).subscribe({
      next: () => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.message.set('Recurring bill saved.');
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Saving the bill'),
    });
  }

  askRemove(r: TemplateView): void {
    this.confirmingId.set(r.id);
  }

  keep(): void {
    this.confirmingId.set(null);
  }

  remove(r: TemplateView): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.posted.set(null);
    this.api.templateDelete(r.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.confirmingId.set(null);
        this.message.set('Recurring bill removed. Bills already posted are kept.');
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Removing the bill'),
    });
  }

  post(): void {
    this.posting.set(true);
    this.error.set('');
    this.api.materialise().subscribe({
      next: (res) => {
        this.posting.set(false);
        this.posted.set(res);
        this.reload();
      },
      error: (e: HttpErrorResponse) => {
        this.posting.set(false);
        this.fail(e, 'Posting bills');
      },
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
