import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, map, of, switchMap } from 'rxjs';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { formatExactPaise } from '../expense/amount-entry';
import { FpCardComponent } from '../ui/fp-card.component';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpProfileLinkComponent } from '../ui/fp-profile-link.component';
import { FpStatComponent } from '../ui/fp-stat.component';
import { errorMessage } from '../plan/plan-format';
import { LoanApiService } from './loan-api.service';
import { dmy, ordinalDay, loanTypeLabel, rateToPctText } from './loan-format';
import { LoanDetailView } from './loan.models';

const PREVIEW_ROWS = 12;

/** FR-13 detail. Every figure is from GET /loans/{id}/detail; the stated EMI is shown as the one that applies. */
@Component({
  selector: 'app-loan-detail',
  standalone: true,
  imports: [RouterLink, FpCardComponent, FpPageHeaderComponent, FpProfileLinkComponent, FpStatComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './loan.css',
  template: `
    <div class="fp-page">
      <fp-page-header title="Loan"><fp-profile-link fpTrailing /></fp-page-header>
      <a class="fp-link back" routerLink="/debt/loans">All loans</a>
      @if (error()) {
        <p class="error fp-alert" role="alert">{{ error() }}</p>
      }
      @if (loading()) {
        <p class="fp-note" role="status">Loading loan...</p>
      }
      @if (d(); as l) {
        <fp-card variant="hero">
          <h2 class="lender fp-num">{{ l.lender }} <span class="fp-badge">{{ typeLabel(l.type) }}</span></h2>
          <fp-stat [label]="l.type === 'bullet' ? 'Monthly interest' : 'EMI'" [value]="exact(l.emiUsedPaise)" size="lg"
            [hint]="l.emiDerived ? 'Calculated, not from lender' : 'From your lender'" />
          <p class="edu fp-note">Educational view of your own figures. It is not advice to take, prepay or change any loan.</p>
        </fp-card>
        @if (l.closed) { <p class="note fp-warn" role="status">This loan is closed.</p> }
        @if (l.pastDue) { <p class="note fp-warn" role="status">The principal due date has passed.</p> }

        <fp-card>
          <dl class="facts fp-dl">
            <div>
              <dt>{{ l.type === 'bullet' ? 'Monthly interest' : 'EMI' }}</dt>
              <dd>{{ exact(l.emiUsedPaise) }}
                @if (l.emiDerived) { <span class="fp-badge warn">Calculated, not from lender</span> } @else { <span class="fp-badge">From your lender</span> }
              </dd>
            </div>
            @if (l.computedEmiComparison; as c) {
              <div>
                <dt>{{ c.label }}</dt>
                <dd>{{ exact(c.emiPaise) }}</dd>
              </div>
            }
            <div><dt>Effective rate</dt><dd>{{ pct(l.effectiveAnnualRate) }} a year</dd></div>
            <div><dt>Interest still to pay</dt><dd>{{ exact(l.interestRemainingPaise) }}</dd></div>
            <div><dt>Total still payable</dt><dd>{{ exact(l.totalRemainingPayablePaise) }}</dd></div>
            <div><dt>Payments left</dt><dd>{{ l.instalmentsRemaining }}</dd></div>
            @if (l.dueDay) {
              <div><dt>Payment due on</dt><dd>{{ ordinal(l.dueDay) }} of each month</dd></div>
            }
            @if (l.payoffCycle; as p) {
              <div><dt>Final payment in the cycle starting</dt><dd>{{ date(p.start) }}</dd></div>
            }
          </dl>
          @if (l.balloonFinalPayment) {
            <p class="note fp-warn">The last payment includes the whole principal.</p>
          }
          <p class="fp-note fp-small">Your lender's EMI is used for everything on this screen. The calculated figure is shown only for comparison and may differ because of fees or rounding.</p>
        </fp-card>

        @if (l.instalments.length > 0) {
          <fp-card heading="Remaining payments">
            <div class="fp-table-wrap" tabindex="0" role="region" aria-label="Remaining payments table">
              <table class="fp-table nowrap">
                <caption class="sr-only">Remaining payments for {{ l.lender }}</caption>
                <thead>
                  <tr><th scope="col">#</th><th scope="col">Payment</th><th scope="col">Interest</th><th scope="col">Principal</th><th scope="col">Balance after</th></tr>
                </thead>
                <tbody>
                  @for (i of shown(); track i.number) {
                    <tr>
                      <th scope="row">{{ i.number }}</th>
                      <td>{{ exact(i.paymentPaise) }}</td>
                      <td>{{ exact(i.interestPaise) }}</td>
                      <td>{{ exact(i.principalPaise) }}</td>
                      <td>{{ exact(i.closingPaise) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (l.instalments.length > preview) {
              <button type="button" class="fp-pill more" [attr.aria-expanded]="all()" (click)="all.set(!all())">
                {{ all() ? 'Show fewer' : 'Show all ' + l.instalments.length + ' payments' }}
              </button>
            }
          </fp-card>
        }
        <nav aria-label="More about this loan">
          <ul class="fp-list">
            <li><a class="fp-row link" [routerLink]="['/debt/loans', l.id, 'interest']">Principal vs interest</a></li>
            @if (l.type === 'emi') { <li><a class="fp-row link" [routerLink]="['/debt/loans', l.id, 'prepay']">Prepayment what-if</a></li> }
            @if (l.type === 'bullet') { <li><a class="fp-row link" [routerLink]="['/debt/loans', l.id, 'sinking-fund']">Sinking fund</a></li> }
          </ul>
        </nav>
      }
    </div>
  `,
})
export class LoanDetailComponent {
  private readonly api = inject(LoanApiService);
  readonly d = signal<LoanDetailView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly all = signal(false);
  readonly preview = PREVIEW_ROWS;
  readonly shown = computed(() => {
    const rows = this.d()?.instalments ?? [];
    return this.all() ? rows : rows.slice(0, PREVIEW_ROWS);
  });
  readonly exact = formatExactPaise;
  readonly typeLabel = loanTypeLabel;
  readonly pct = rateToPctText;
  readonly date = dmy;
  readonly ordinal = ordinalDay;

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        map((p) => p.get('id') ?? ''),
        switchMap((id) => {
          this.loading.set(true);
          this.error.set('');
          this.d.set(null);
          this.all.set(false);
          return this.api.detail(id).pipe(
            catchError((e: HttpErrorResponse) => {
              this.error.set(errorMessage(e.status, 'Loading the loan'));
              return of(null);
            }),
          );
        }),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((v) => {
        this.d.set(v);
        this.loading.set(false);
      });
  }
}
