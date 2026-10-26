import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, of, switchMap } from 'rxjs';
import { RouterLink } from '@angular/router';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { errorMessage } from '../plan/plan-format';
import { formatExactPaise } from '../expense/amount-entry';
import { FpCardComponent } from '../ui/fp-card.component';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';
import { FpStatComponent } from '../ui/fp-stat.component';
import { GuardApiService } from './guard-api.service';
import { LoanApiService, loansWithTotal } from './loan-api.service';
import { LoanFormComponent } from './loan-form.component';
import { dmy, ordinalDay, loanTypeLabel, rateToPctText } from './loan-format';
import { LoanView } from './loan.models';

/** FR-12 list. The total EMI is the server's guard figure; nothing is summed here. */
@Component({
  selector: 'app-loan-list',
  standalone: true,
  imports: [RouterLink, LoanFormComponent, PaiseInrPipe, FpCardComponent, FpPageHeaderComponent, FpProfileLinkComponent, FpStatComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './loan.css',
  template: `
    <div class="fp-page">
      <fp-page-header title="Your loans"><fp-profile-link fpTrailing /></fp-page-header>
      <p class="edu fp-note">Educational view of your own figures. It is not advice to take, prepay or change any loan.</p>
      @if (error()) {
        <p class="error fp-alert" role="alert">{{ error() }}</p>
        <button type="button" class="fp-pill retry" (click)="load()">Retry</button>
      }
      @if (loading()) {
        <p class="fp-note" role="status">Loading loans...</p>
      } @else {
        @if (loans().length === 0 && !error()) {
          <p class="fp-note">No loans added yet.</p>
        }
        @if (loans().length > 0) {
          @if (totalEmiPaise() !== null) {
            <fp-card variant="hero" class="total" role="status">
              <fp-stat label="Total EMI a month" [value]="exact(totalEmiPaise()!)" size="lg" />
            </fp-card>
          }
          <ul class="fp-list" aria-label="Loans">
            @for (l of loans(); track l.id) {
              <li class="loan">
                <fp-card>
                  <a [routerLink]="['/debt/loans', l.id]" class="card-link">
                    <span class="lender">{{ l.lender }}</span>
                    <span class="fp-badge">{{ typeLabel(l.type) }}</span>
                  </a>
                  <dl class="fp-dl">
                    <div><dt>Outstanding</dt><dd>{{ l.principalOutstandingPaise | paiseInr }}</dd></div>
                    <div>
                      <dt>{{ l.type === 'bullet' ? 'Monthly interest' : 'EMI' }}</dt>
                      <dd>{{ l.emiPaise === null ? 'n/a' : exact(l.emiPaise) }}
                        @if (l.emiDerived) { <span class="fp-badge warn">Calculated, not from lender</span> }
                      </dd>
                    </div>
                    <div><dt>Rate</dt><dd>{{ pct(l.rate) }} a year</dd></div>
                    @if (l.type === 'bullet' && l.bulletDueDate) {
                      <div><dt>Principal due</dt><dd>{{ date(l.bulletDueDate) }}</dd></div>
                    } @else if (l.emisLeft !== null) {
                      <div><dt>EMIs left</dt><dd>{{ l.emisLeft }}</dd></div>
                    }
                    @if (l.dueDay) {
                      <div><dt>Due on</dt><dd>the {{ ordinal(l.dueDay) }}</dd></div>
                    }
                  </dl>
                  @if (confirming() === l.id) {
                    <div class="fp-actions" role="group" [attr.aria-label]="'Remove ' + l.lender + '?'">
                      <span class="ask">Remove?</span>
                      <button type="button" class="fp-btn" [disabled]="removing()" [attr.aria-label]="'Yes, remove ' + l.lender" (click)="remove(l)">Yes</button>
                      <button type="button" class="fp-pill" [disabled]="removing()" (click)="confirming.set(null)">No</button>
                    </div>
                  } @else {
                    <div class="fp-actions">
                      <button type="button" class="fp-pill" [attr.aria-label]="'Remove ' + l.lender" (click)="confirming.set(l.id)">Remove</button>
                    </div>
                  }
                </fp-card>
              </li>
            }
          </ul>
        }
      }
      <fp-card>
        <details class="add" [open]="loans().length === 0 && !loading()">
          <summary>Add a loan</summary>
          <app-loan-form (saved)="load()" />
        </details>
      </fp-card>
    </div>
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
  readonly ordinal = ordinalDay;

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
