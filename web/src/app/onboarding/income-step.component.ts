import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { formatExactPaise } from '../expense/amount-entry';
import { todayIst } from '../expense/add-expense.component';
import { PlanApiService } from '../plan/plan-api.service';
import { errorMessage, rupeesTextToPaise } from '../plan/plan-format';
import { CycleTotalView, INCOME_TYPES, IncomeType, IncomeView } from '../plan/plan.models';
import { PlanStepsState } from './plan-steps.state';

@Component({
  selector: 'app-income-step',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="muted">Add each monthly income: salary, rent you receive, anything regular. You can edit these later under More.</p>
    <label for="inc-type">Type</label>
    <select id="inc-type" [value]="type()" (change)="type.set(asType(val($event)))">
      @for (t of types; track t.code) {
        <option [value]="t.code" [selected]="t.code === type()">{{ t.label }}</option>
      }
    </select>
    <label for="inc-amt">Amount per month (rupees)</label>
    <input id="inc-amt" type="text" inputmode="decimal" autocomplete="off" [value]="amountText()" (input)="amountText.set(val($event))" />
    <button type="button" class="btn" [disabled]="amountPaise() === null || saving()" [attr.aria-busy]="saving()" (click)="add()">Add income</button>
    @if (items().length > 0) {
      <ul class="rows" aria-label="Income added">
        @for (i of items(); track i.id) {
          <li>{{ label(i.type) }}: {{ exact(i.amountPaise) }} a month</li>
        }
      </ul>
    }
    @if (total(); as t) {
      <p role="status">Income this cycle: <strong>{{ exact(t.totalPaise) }}</strong></p>
    }
    <p class="error" role="alert">{{ error() }}</p>
  `,
  styleUrl: './onboarding.css',
})
export class IncomeStepComponent implements OnInit {
  private readonly api = inject(PlanApiService);
  private readonly state = inject(PlanStepsState);
  readonly types = INCOME_TYPES;
  readonly exact = formatExactPaise;
  readonly label = (c: IncomeType) => INCOME_TYPES.find((t) => t.code === c)?.label ?? c;

  readonly items = signal<IncomeView[]>([]);
  readonly total = signal<CycleTotalView | null>(null);
  readonly type = signal<IncomeType>('SALARY');
  readonly amountText = signal('');
  readonly saving = signal(false);
  readonly error = signal('');
  readonly amountPaise = computed(() => rupeesTextToPaise(this.amountText()));

  ngOnInit(): void {
    this.reload();
  }

  asType(v: string): IncomeType {
    return INCOME_TYPES.find((t) => t.code === v)?.code ?? 'SALARY';
  }

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  private reload(): void {
    forkJoin({ rows: this.api.incomeList(), total: this.api.incomeCycleTotal() }).subscribe({
      next: ({ rows, total }) => {
        this.items.set(rows);
        this.total.set(total);
        this.state.incomeCount.set(rows.length);
      },
      error: (e: HttpErrorResponse) => this.error.set(errorMessage(e.status, 'Loading income')),
    });
  }

  add(): void {
    const amountPaise = this.amountPaise();
    if (amountPaise === null || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.api.incomeCreate({ type: this.type(), frequency: 'MONTHLY', amountPaise, startDate: todayIst(), endDate: null }).subscribe({
      next: () => {
        this.saving.set(false);
        this.amountText.set('');
        this.reload();
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(errorMessage(e.status, 'Saving income'));
      },
    });
  }
}
