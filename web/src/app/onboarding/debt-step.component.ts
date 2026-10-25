import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, of, switchMap } from 'rxjs';
import { formatExactPaise } from '../expense/amount-entry';
import { GuardApiService } from '../debt/guard-api.service';
import { LoanApiService, loansWithTotal } from '../debt/loan-api.service';
import { LoanFormComponent } from '../debt/loan-form.component';
import { loanTypeLabel } from '../debt/loan-format';
import { LoanView } from '../debt/loan.models';
import { PlanStepsState } from './plan-steps.state';

/**
 * T55 wizard step: add each loan. The total EMI shown is the server's own figure (debt-trap guard endpoint),
 * refreshed after every add; nothing is summed here. Skippable: a debt-free user just presses Next.
 */
@Component({
  selector: 'app-debt-step',
  standalone: true,
  imports: [LoanFormComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './onboarding.css',
  template: `
    <p class="muted">Add each loan you are paying: home, car, personal, gold or card EMI. Skip this if you have none. Use the EMI your lender charges. Never enter account or card numbers.</p>
    <app-loan-form (saved)="added()" />
    @if (items().length > 0) {
      <ul class="rows" aria-label="Loans added">
        @for (l of items(); track l.id) {
          <li>{{ l.lender }} ({{ type(l.type) }}): {{ l.emiPaise === null ? 'no EMI' : exact(l.emiPaise) }}{{ l.emiDerived ? ' (calculated)' : '' }}</li>
        }
      </ul>
    }
    @if (totalEmiPaise() !== null) {
      <p role="status">Total EMI a month: <strong>{{ exact(totalEmiPaise()!) }}</strong></p>
    }
  `,
})
export class DebtStepComponent implements OnInit {
  private readonly loans = inject(LoanApiService);
  private readonly guard = inject(GuardApiService);
  private readonly state = inject(PlanStepsState);
  readonly items = signal<LoanView[]>([]);
  readonly totalEmiPaise = signal<number | null>(null);
  readonly exact = formatExactPaise;
  readonly type = loanTypeLabel;

  private readonly reload$ = new Subject<void>();

  constructor() {
    this.reload$
      .pipe(
        switchMap(() => loansWithTotal(this.loans, this.guard).pipe(catchError(() => of(null)))),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((r) => {
        this.items.set(r?.rows ?? this.items());
        this.state.loanCount.set(this.items().length);
        this.totalEmiPaise.set(r?.totalEmiPaise ?? null);
      });
  }

  ngOnInit(): void {
    this.reload$.next();
  }

  added(): void {
    this.reload$.next();
  }
}
