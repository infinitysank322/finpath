import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, Injector, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService, SIGNIN_STORAGE } from '../auth/auth.service';
import { DRAFT_KEY, clearDraft, saveDraft, takeDraft } from './draft';
import { formatExactPaise, formatTyped, pressKey, toPaise } from './amount-entry';
import { ExpenseApiService } from './expense-api.service';
import { ExpenseQueueService, QUEUE_OWNER } from './expense-queue.service';
import { FpChipGridComponent } from '../ui/fp-chip-grid.component';
import { FpKeypadComponent } from '../ui/fp-keypad.component';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpSegmentedComponent } from '../ui/fp-segmented.component';
import { PendingExpensesComponent } from './pending-expenses.component';
import { MODES, PaymentMode, SpendTag, TAGS } from './expense.models';

export { DRAFT_KEY };

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
  imports: [PendingExpensesComponent, RouterLink, FpPageHeaderComponent, FpChipGridComponent, FpSegmentedComponent, FpKeypadComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './add-expense.component.html',
  styleUrl: './add-expense.component.css',
})
export class AddExpenseComponent {
  private readonly api = inject(ExpenseApiService);
  private readonly queue = inject(ExpenseQueueService);
  private readonly injector = inject(Injector);
  private readonly draftStorage = inject(SIGNIN_STORAGE);
  private readonly owner = inject(QUEUE_OWNER);
  /** The sign-in this entry belongs to, captured at save time (a rejected refresh clears the session mid-save). */
  private entryOwner: string | null = null;

  readonly categories = this.api.categories();
  readonly modes = MODES;
  readonly tags = TAGS;
  readonly categoryOptions = this.categories.map((c) => ({ value: c.code, label: c.name }));
  readonly modeOptions = MODES.map((m) => ({ value: m.code, label: m.label }));
  readonly tagOptions = TAGS.map((x) => ({ value: x.code, label: x.label }));

  readonly amountText = signal('');
  readonly category = signal<string | null>(null);
  readonly mode = signal<PaymentMode | null>(null);
  readonly tag = signal<SpendTag | null>(null);
  readonly note = signal('');
  readonly saving = signal(false);
  readonly message = signal('');
  readonly error = signal('');
  /** Nobody is signed in: the entry cannot be saved or queued; the form is kept and sign-in is offered. */
  readonly signInNeeded = signal(false);

  constructor() {
    this.restoreDraft();
  }

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

  press(k: string): void {
    this.amountText.update((t) => pressKey(t, k));
    this.message.set('');
    this.error.set('');
  }

  onMode(code: string): void {
    this.mode.set(code as PaymentMode);
  }

  /** null = the selected tag was pressed again (the tag is optional). */
  onTag(code: string | null): void {
    this.tag.set(code as SpendTag | null);
  }

  onNote(event: Event): void {
    this.note.set((event.target as HTMLInputElement).value);
  }

  save(): void {
    const category = this.category();
    const mode = this.mode();
    if (!this.canSave() || category === null || mode === null) return;
    this.signInNeeded.set(false);
    if (!this.queue.hasOwner()) {
      this.askToSignIn();
      return;
    }
    this.entryOwner = this.owner();
    // Held until the save is known to have worked, so a refresh rejected mid-save (forced sign-out) loses nothing.
    saveDraft(this.draftStorage, this.draftFields(), this.entryOwner);
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
    const name = this.categories.find((c) => c.code === category)?.name ?? category;
    const queueIt = () => {
      const persisted = this.queue.enqueue(input, this.key);
      this.saving.set(false);
      clearDraft(this.draftStorage);
      if (!persisted) {
        this.error.set('This device could not store the expense for later. Keep the app open until it uploads.');
      }
      this.message.set(`Saved ₹${typed} under ${name}. Pending sync: it will upload when you are back online.`);
      this.reset();
    };
    if (!this.queue.isOnline()) {
      queueIt();
      return;
    }
    this.api
      .create(input, this.key)
      .subscribe({
        next: () => {
          this.saving.set(false);
          clearDraft(this.draftStorage);
          this.message.set(`Saved ₹${typed} under ${name}.`);
          this.reset();
        },
        error: (e: HttpErrorResponse) => {
          if (e.status === 0 || (e as Error).name === 'TimeoutError') {
            queueIt(); // no response at all: keep the entry and let the queue retry with the same key
            return;
          }
          this.saving.set(false);
          this.failedSig = sig;
          if (e.status === 401) {
            this.askToSignIn(); // the draft written above stays: it is restored for this user after sign-in
            return;
          }
          clearDraft(this.draftStorage); // the user is still here with the form; no draft needed
          this.error.set(
            e.status === 401
              ? 'Sign in to save this expense.'
              : e.status === 400
              ? 'This expense was rejected. Check the amount and note, and do not enter account or card numbers.'
              : e.status === 409
                ? 'This entry conflicts with an earlier one. Change something and try again.'
                : e.status === 403
                  ? 'Consent is needed before expenses can be saved.'
                  : e.status >= 500
                    ? 'The server had a problem and the expense was not saved. Please try again in a moment.'
                    : 'Could not save. Please try again.',
          );
          if (e.status === 409 || e.status === 400) {
            this.key = newKey();
            this.failedSig = null;
          }
        },
      });
  }

  /** Keep what was typed, remember this screen, and go to /sign-in. */
  signInToSave(): void {
    saveDraft(this.draftStorage, this.draftFields(), this.entryOwner);
    this.injector.get(AuthService).rememberReturnTo('/spending/add');
    void this.injector.get(Router).navigateByUrl('/sign-in');
  }

  private draftFields() {
    return { amountText: this.amountText(), category: this.category(), mode: this.mode(), tag: this.tag(), note: this.note() };
  }

  private askToSignIn(): void {
    this.saving.set(false);
    this.message.set('');
    this.error.set('Sign in to save this expense. What you entered stays here.');
    this.signInNeeded.set(true);
  }

  private restoreDraft(): void {
    const d = takeDraft(this.draftStorage, this.owner());
    if (!d) return;
    this.entryOwner = d.userId;
    if (typeof d.amountText === 'string' && /^[0-9.]{0,16}$/.test(d.amountText)) this.amountText.set(d.amountText);
    if (typeof d.category === 'string' && this.categories.some((c) => c.code === d.category)) this.category.set(d.category);
    const mode = this.modes.find((m) => m.code === d.mode);
    if (mode) this.mode.set(mode.code);
    const tag = this.tags.find((t) => t.code === d.tag);
    if (tag) this.tag.set(tag.code);
    if (typeof d.note === 'string') this.note.set(d.note.slice(0, 140));
  }

  private reset(): void {
    this.signInNeeded.set(false);
    this.amountText.set('');
    this.category.set(null);
    this.mode.set(null);
    this.tag.set(null);
    this.note.set('');
    this.key = newKey();
    this.failedSig = null;
  }
}
