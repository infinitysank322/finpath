import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { formatExactPaise } from '../expense/amount-entry';
import { todayIst } from '../expense/add-expense.component';
import { OUT_CATEGORIES } from '../expense/expense.models';
import { PlanApiService } from '../plan/plan-api.service';
import { errorMessage, rupeesTextToPaise } from '../plan/plan-format';
import { RECURRING_FREQUENCIES, RecurringFrequency, TemplateView } from '../plan/plan.models';

@Component({
  selector: 'app-fixed-expenses-step',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="muted">Add bills that repeat: rent, insurance, school fees, subscriptions. Skip this if you have none; you can add them later under More.</p>
    <label for="fx-cat">Category</label>
    <select id="fx-cat" (change)="category.set(val($event))">
      @for (c of categories; track c.code) {
        <option [value]="c.code" [selected]="c.code === category()">{{ c.name }}</option>
      }
    </select>
    <label for="fx-freq">How often</label>
    <select id="fx-freq" (change)="frequency.set(asFreq(val($event)))">
      @for (f of frequencies; track f.code) {
        <option [value]="f.code" [selected]="f.code === frequency()">{{ f.label }}</option>
      }
    </select>
    <label for="fx-amt">Bill amount (rupees, per period)</label>
    <input id="fx-amt" type="text" inputmode="decimal" autocomplete="off" [value]="amountText()" (input)="amountText.set(val($event))" />
    <button type="button" class="fp-pill" [disabled]="amountPaise() === null || saving()" [attr.aria-busy]="saving()" (click)="add()">Add bill</button>
    @if (items().length > 0) {
      <ul class="rows" aria-label="Bills added">
        @for (t of items(); track t.id) {
          <li>
            {{ name(t.categoryCode) }}: {{ exact(t.amountPaise) }} {{ every(t.frequency) }}
            @if (t.currentInstalmentPaise !== null && t.frequency !== 'MONTHLY') {
              ({{ exact(t.currentInstalmentPaise) }} each cycle)
            }
          </li>
        }
      </ul>
    }
    <p class="error" role="alert">{{ error() }}</p>
  `,
  styleUrl: './onboarding.css',
})
export class FixedExpensesStepComponent implements OnInit {
  private readonly api = inject(PlanApiService);
  readonly categories = OUT_CATEGORIES;
  readonly frequencies = RECURRING_FREQUENCIES;
  readonly exact = formatExactPaise;
  readonly name = (c: string) => OUT_CATEGORIES.find((x) => x.code === c)?.name ?? c;
  readonly every = (f: RecurringFrequency) => RECURRING_FREQUENCIES.find((x) => x.code === f)?.every ?? '';

  readonly items = signal<TemplateView[]>([]);
  readonly category = signal('RENT');
  readonly frequency = signal<RecurringFrequency>('MONTHLY');
  readonly amountText = signal('');
  readonly saving = signal(false);
  readonly error = signal('');
  readonly amountPaise = computed(() => rupeesTextToPaise(this.amountText()));

  ngOnInit(): void {
    this.reload();
  }

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }
  asFreq(v: string): RecurringFrequency {
    return RECURRING_FREQUENCIES.find((f) => f.code === v)?.code ?? 'MONTHLY';
  }

  private reload(): void {
    this.api.templateList().subscribe({
      next: (rows) => this.items.set(rows),
      error: (e: HttpErrorResponse) => this.error.set(errorMessage(e.status, 'Loading bills')),
    });
  }

  add(): void {
    const amountPaise = this.amountPaise();
    if (amountPaise === null || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.api
      .templateCreate({
        categoryCode: this.category(),
        amountPaise,
        frequency: this.frequency(),
        mode: 'UPI',
        tag: 'NEED',
        startDate: todayIst(),
        endDate: null,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.amountText.set('');
          this.reload();
        },
        error: (e: HttpErrorResponse) => {
          this.saving.set(false);
          this.error.set(errorMessage(e.status, 'Saving the bill'));
        },
      });
  }
}
