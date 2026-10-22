import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { formatExactPaise } from './amount-entry';
import { ExpenseApiService } from './expense-api.service';
import { ExpenseQueueService } from './expense-queue.service';
import { MODES, PaymentMode } from './expense.models';

/** Locally queued expenses: 'pending sync' items and rejected ones that need the user's attention. */
@Component({
  selector: 'app-pending-expenses',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pending-expenses.component.html',
  styleUrl: './pending-expenses.component.css',
})
export class PendingExpensesComponent {
  readonly queue = inject(ExpenseQueueService);
  private readonly cats = inject(ExpenseApiService).categories();
  readonly exact = formatExactPaise;
  readonly modeLabel = (code: PaymentMode) => MODES.find((m) => m.code === code)?.label ?? code;
  readonly categoryName = (code: string) => this.cats.find((c) => c.code === code)?.name ?? code;
}
