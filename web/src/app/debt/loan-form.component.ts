import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { todayIst } from '../expense/add-expense.component';
import { errorMessage, rupeesTextToPaise } from '../plan/plan-format';
import { GuardDialogComponent } from './guard-dialog.component';
import { GuardView } from './guard.models';
import { LoanApiService } from './loan-api.service';
import { LOAN_TYPES, pctToRate } from './loan-format';
import { DebtTrapBlockedBody, LoanInput, LoanType, LoanView } from './loan.models';

/**
 * Add-a-loan form (FR-12), used by the onboarding debt step and the loan list. The stated EMI is sent as typed
 * and is the source of truth; leave it empty only for an EMI or bullet loan and the server derives and flags one.
 * No account or card number is asked for. Lender is free text. A 409 debt_trap_blocked opens the guard dialog and
 * only the explicit confirm re-sends with confirmBlock.
 */
@Component({
  selector: 'app-loan-form',
  standalone: true,
  imports: [GuardDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './loan.css',
  template: `
    <form class="loan-form" (submit)="$event.preventDefault(); save()">
      <label for="ln-lender">Lender (any name you like)</label>
      <input id="ln-lender" type="text" maxlength="60" autocomplete="off" [value]="lender()" (input)="lender.set(val($event))" />

      <label for="ln-type">Type of loan</label>
      <select id="ln-type" (change)="type.set(asType(val($event)))">
        @for (t of types; track t.code) {
          <option [value]="t.code" [selected]="t.code === type()">{{ t.label }}</option>
        }
      </select>
      <p class="muted small">{{ typeHint() }}</p>

      <label for="ln-principal">Outstanding principal (rupees)</label>
      <input id="ln-principal" type="text" inputmode="decimal" autocomplete="off" [value]="principalText()" (input)="principalText.set(val($event))" />

      <label for="ln-rate">Interest rate (% a year)</label>
      <input id="ln-rate" type="text" inputmode="decimal" autocomplete="off" [value]="rateText()" (input)="rateText.set(val($event))" />

      <label for="ln-emi">{{ isBullet() ? 'Monthly interest (rupees, optional)' : 'EMI your lender charges (rupees)' }}</label>
      <input id="ln-emi" type="text" inputmode="decimal" autocomplete="off" [value]="emiText()" (input)="emiText.set(val($event))" />
      <p class="muted small">{{ emiHint() }}</p>

      @if (isBullet()) {
        <label for="ln-due">Principal due on</label>
        <input id="ln-due" type="date" [min]="today" [value]="dueDate()" (input)="dueDate.set(val($event))" />
      } @else {
        <label for="ln-left">EMIs left</label>
        <input id="ln-left" type="text" inputmode="numeric" autocomplete="off" [value]="emisLeftText()" (input)="emisLeftText.set(val($event))" />
      }
      @if (type() === 'card_emi') {
        <label for="ln-fee">Upfront fee (rupees, optional)</label>
        <input id="ln-fee" type="text" inputmode="decimal" autocomplete="off" [value]="feeText()" (input)="feeText.set(val($event))" />
      }

      <label for="ln-dueday">Due day of month (optional)</label>
      <input id="ln-dueday" type="text" inputmode="numeric" autocomplete="off" maxlength="2" aria-describedby="ln-dueday-help" [attr.aria-invalid]="dueDayInvalid()" [value]="dueDayText()" (input)="dueDayText.set(val($event))" />
      <p id="ln-dueday-help" class="muted small">Reminders arrive 5 days before. Leave empty for none.</p>
      @if (dueDayInvalid()) {
        <p class="error" role="alert">Enter a day from 1 to 31, or leave it empty.</p>
      }

      <button type="submit" class="btn primary" [disabled]="input() === null || saving()" [attr.aria-busy]="saving()">Add loan</button>
    </form>
    <p class="error" role="alert">{{ error() }}</p>
    <app-guard-dialog [guard]="blocked()" (confirmed)="confirm()" (cancelled)="blocked.set(null)" />
  `,
})
export class LoanFormComponent {
  private readonly api = inject(LoanApiService);
  readonly saved = output<LoanView>();

  readonly types = LOAN_TYPES;
  readonly today = todayIst();
  readonly lender = signal('');
  readonly type = signal<LoanType>('emi');
  readonly principalText = signal('');
  readonly rateText = signal('');
  readonly emiText = signal('');
  readonly emisLeftText = signal('');
  readonly dueDate = signal('');
  readonly feeText = signal('');
  readonly dueDayText = signal('');
  readonly saving = signal(false);
  readonly error = signal('');
  /** Set while the guard dialog is open for a loan the server refused (409). */
  readonly blocked = signal<GuardView | null>(null);

  /** True when text is entered that is not a whole number 1-31. Empty is valid (no reminder). */
  readonly dueDayInvalid = computed(() => {
    const t = this.dueDayText().trim();
    return t !== '' && !/^([1-9]|[12]\d|3[01])$/.test(t);
  });

  readonly isBullet = computed(() => this.type() === 'bullet');
  readonly typeHint = computed(() => LOAN_TYPES.find((t) => t.code === this.type())?.hint ?? '');
  readonly emiHint = computed(() =>
    this.type() === 'card_emi'
      ? 'Required: type the instalment from your card statement.'
      : 'Type the figure your lender charges. If you leave it empty we calculate one and mark it as calculated.',
  );

  /** The request body, or null while the form is incomplete. Text to paise/integer conversion only. */
  readonly input = computed<LoanInput | null>(() => {
    const lender = this.lender().trim();
    const type = this.type();
    const rate = pctToRate(this.rateText());
    const principal = this.principalText().trim() === '0' ? 0 : rupeesTextToPaise(this.principalText());
    if (!lender || rate === null || principal === null) return null;
    const emi = this.emiText().trim() === '' ? null : rupeesTextToPaise(this.emiText());
    if (this.emiText().trim() !== '' && emi === null) return null;
    if (type === 'card_emi' && emi === null) return null;
    let emisLeft: number | null = null;
    let due: string | null = null;
    if (type === 'bullet') {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(this.dueDate())) return null;
      due = this.dueDate();
    } else {
      if (!/^[1-9]\d{0,3}$/.test(this.emisLeftText().trim())) return null;
      emisLeft = parseInt(this.emisLeftText().trim(), 10);
    }
    let fee: number | null = null;
    if (type === 'card_emi' && this.feeText().trim() !== '') {
      fee = rupeesTextToPaise(this.feeText());
      if (fee === null) return null;
    }
    if (this.dueDayInvalid()) return null;
    const dueDay = this.dueDayText().trim() === '' ? null : parseInt(this.dueDayText().trim(), 10);
    return { lender, type, principalOutstandingPaise: principal, rate, emiPaise: emi, emisLeft, bulletDueDate: due, upfrontFeePaise: fee, dueDay };
  });

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }
  asType(v: string): LoanType {
    return LOAN_TYPES.find((t) => t.code === v)?.code ?? 'emi';
  }

  save(): void {
    this.submit(false);
  }

  confirm(): void {
    this.blocked.set(null);
    this.submit(true);
  }

  private submit(confirmBlock: boolean): void {
    const body = this.input();
    if (body === null || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.api.create(body, confirmBlock).subscribe({
      next: (loan) => {
        this.saving.set(false);
        this.reset();
        this.saved.emit(loan);
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        const b = e.error as Partial<DebtTrapBlockedBody> | null;
        if (e.status === 409 && b?.error === 'debt_trap_blocked' && b.guard) {
          this.blocked.set(b.guard);
        } else {
          this.error.set(errorMessage(e.status, 'Saving the loan'));
        }
      },
    });
  }

  private reset(): void {
    for (const s of [this.lender, this.principalText, this.rateText, this.emiText, this.emisLeftText, this.dueDate, this.feeText, this.dueDayText]) s.set('');
  }
}
