import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, of, switchMap } from 'rxjs';
import { RouterLink } from '@angular/router';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { errorMessage } from '../plan/plan-format';
import { formatExactPaise } from '../expense/amount-entry';
import { GuardApiService } from './guard-api.service';
import { LoanApiService, loansWithTotal } from './loan-api.service';
import { LoanFormComponent } from './loan-form.component';
import { dmy, loanTypeLabel, rateToPctText } from './loan-format';
import { LoanView } from './loan.models';

/** FR-12 list. The total EMI is the server's guard figure; nothing is summed here. */
@Component({
  selector: 'app-loan-list',
  standalone: true,
  imports: [RouterLink, LoanFormComponent, PaiseInrPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './loan.css',
  template: `
    <h1>Your loans</h1>
    <p class="edu">Educational view of your own figures. It is not advice to take, prepay or change any loan.</p>
    @if (error()) {
      <p class="error" role="alert">{{ error() }}</p>
      <button type="button" class="btn" (click)="load()">Retry</button>
    }
    @if (loading()) {
      <p class="muted" role="status">Loading loans...</p>
    } @else {
      @if (loans().length === 0 && !error()) {
        <p class="muted">No loans added yet.</p>
      }
      @if (loans().length > 0) {
        @if (totalEmiPaise() !== null) {
          <p role="status" class="total">Total EMI a month: <strong>{{ exact(totalEmiPaise()!) }}</strong></p>
        }
        <ul class="cards" aria-label="Loans">
          @for (l of loans(); track l.id) {
            <li class="card">
              <a [routerLink]="['/debt/loans', l.id]" class="card-link">
                <span class="lender">{{ l.lender }}</span>
                <span class="chip">{{ typeLabel(l.type) }}</span>
              </a>
              <dl>
                <div><dt>Outstanding</dt><dd>{{ l.principalOutstandingPaise | paiseInr }}</dd></div>
                <div>
                  <dt>{{ l.type === 'bullet' ? 'Monthly interest' : 'EMI' }}</dt>
                  <dd>{{ l.emiPaise === null ? 'n/a' : exact(l.emiPaise) }}
                    @if (l.emiDerived) { <span class="chip warn">Calculated, not from lender</span> }
                  </dd>
                </div>
                <div><dt>Rate</dt><dd>{{ pct(l.rate) }} a year</dd></div>
                @if (l.type === 'bullet' && l.bulletDueDate) {
                  <div><dt>Principal due</dt><dd>{{ date(l.bulletDueDate) }}</dd></div>
                } @else if (l.emisLeft !== null) {
                  <div><dt>EMIs left</dt><dd>{{ l.emisLeft }}</dd></div>
                }
              </dl>
              @if (confirming() === l.id) {
                <span role="group" [attr.aria-label]="'Remove ' + l.lender + '?'">
                  Remove?
                  <button type="button" class="btn small-btn" [disabled]="removing()" [attr.aria-label]="'Yes, remove ' + l.lender" (click)="remove(l)">Yes</button>
                  <button type="button" class="btn small-btn" [disabled]="removing()" (click)="confirming.set(null)">No</button>
                </span>
              } @else {
                <button type="button" class="btn small-btn" [attr.aria-label]="'Remove ' + l.lender" (click)="confirming.set(l.id)">Remove</button>
              }
            </li>
          }
        </ul>
      }
    }
    <details class="add" [open]="loans().length === 0 && !loading()">
      <summary>Add a loan</summary>
      <app-loan-form (saved)="load()" />
    </details>
  `,
})
export class LoanListComponent {
  private readonly api = inject(LoanApiService);
  private readonly guard = inject(GuardApiService);
  readonly loans = signal<LoanView[]>([]);
  readonly totalEmiPaise = signal<number | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly exact = formatExactPaise;
  readonly typeLabel = loanTypeLabel;
  readonly pct = rateToPctText;
  readonly date = dmy;

  readonly confirming = signal<string | null>(null);
  readonly removing = signal(false);
  private readonly reload$ = new Subject<void>();

  constructor() {
    this.reload$
      .pipe(
        switchMap(() =>
          loansWithTotal(this.api, this.guard).pipe(
            catchError((e: HttpErrorResponse) => {
              this.error.set(errorMessage(e.status, 'Loading loans'));
              this.totalEmiPaise.set(null);
              this.loading.set(false);
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((r) => {
        if (r) {
          this.loans.set(r.rows);
          this.totalEmiPaise.set(r.totalEmiPaise);
          this.loading.set(false);
        }
      });
    this.load();
  }

  load(): void {
    this.error.set('');
    this.reload$.next();
  }

  remove(l: LoanView): void {
    if (this.removing()) return;
    this.removing.set(true);
    this.api.remove(l.id).subscribe({
      next: () => {
        this.removing.set(false);
        this.confirming.set(null);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.removing.set(false);
        this.confirming.set(null);
        this.error.set(errorMessage(e.status, 'Removing the loan'));
      },
    });
  }
}
