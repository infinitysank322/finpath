import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { formatExactPaise, formatTyped, pressKey, toPaise } from './amount-entry';
import { ExpenseApiService } from './expense-api.service';
import { MODES, PaymentMode, SpendTag, TAGS } from './expense.models';

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', 'back'];

/** yyyy-MM-dd in the Indian calendar day (the cycle is Asia/Kolkata). */
export function todayIst(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(now);
}

function newKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `k${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`;
}

@Component({
  selector: 'app-add-expense',
  standalone: true,
  imports: [],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './add-expense.component.html',
  styleUrl: './add-expense.component.css',
})
export class AddExpenseComponent {
  private readonly api = inject(ExpenseApiService);

  readonly keys = KEYS;
  readonly categories = this.api.categories();
  readonly modes = MODES;
  readonly tags = TAGS;

  readonly amountText = signal('');
  readonly category = signal<string | null>(null);
  readonly mode = signal<PaymentMode | null>(null);
  readonly tag = signal<SpendTag | null>(null);
  readonly note = signal('');
  readonly saving = signal(false);
  readonly message = signal('');
  readonly error = signal('');

  readonly amountPaise = computed(() => toPaise(this.amountText()));
  readonly canSave = computed(
    () => this.amountPaise() > 0 && this.category() !== null && this.mode() !== null && !this.saving(),
  );
  /** Typed text for display, e.g. "2,50,000" grouping is applied by the pipe once paise is known. */
  readonly amountShown = computed(() => formatTyped(this.amountText()));
  readonly amountLabel = computed(() => formatExactPaise(this.amountPaise()));

  // One key per entry attempt: a retry after a network error reuses it so the API can dedupe.
  private key = newKey();
  /** Payload signature of the last failed attempt; the key is reused only for an identical payload. */
  private failedSig: string | null = null;

  keyLabel(k: string): string {
    return k === 'back' ? 'Delete last digit' : k === '.' ? 'Decimal point' : `Digit ${k}`;
  }

  press(k: string): void {
    this.amountText.update((t) => pressKey(t, k));
    this.message.set('');
    this.error.set('');
  }

  toggleTag(t: SpendTag): void {
    this.tag.update((cur) => (cur === t ? null : t));
  }

  onNote(event: Event): void {
    this.note.set((event.target as HTMLInputElement).value);
  }

  save(): void {
    const category = this.category();
    const mode = this.mode();
    if (!this.canSave() || category === null || mode === null) return;
    const amountPaise = this.amountPaise();
    const typed = this.amountText().replace(/\.$/, '');
    this.saving.set(true);
    this.error.set('');
    const input = {
      txnDate: todayIst(),
      amountPaise,
      categoryCode: category,
      mode,
      tag: this.tag(),
      note: this.note().trim() || null,
    };
    const sig = JSON.stringify(input);
    if (this.failedSig !== null && this.failedSig !== sig) this.key = newKey();
    this.failedSig = null;
    this.api
      .create(input, this.key)
      .subscribe({
        next: () => {
          this.saving.set(false);
          const name = this.categories.find((c) => c.code === category)?.name ?? category;
          this.message.set(`Saved ₹${typed} under ${name}.`);
          this.reset();
        },
        error: (e: HttpErrorResponse) => {
          this.saving.set(false);
          this.failedSig = sig;
          this.error.set(
            e.status === 401
              ? 'Please sign in to save expenses.'
              : e.status === 400
              ? 'This expense was rejected. Check the amount and note, and do not enter account or card numbers.'
              : e.status === 409
                ? 'This entry conflicts with an earlier one. Change something and try again.'
                : e.status === 403
                  ? 'Consent is needed before expenses can be saved.'
                  : 'Could not save. Check your connection and try again.',
          );
          if (e.status === 409 || e.status === 400) {
            this.key = newKey();
            this.failedSig = null;
          }
        },
      });
  }

  private reset(): void {
    this.amountText.set('');
    this.category.set(null);
    this.mode.set(null);
    this.tag.set(null);
    this.note.set('');
    this.key = newKey();
    this.failedSig = null;
  }
}
