import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ExpenseQueueService } from '../expense/expense-queue.service';
import { API_BASE } from '../expense/expense-api.service';

/** GET /me/consent (T31). The statement is what the user is shown; `given` is the server's verdict. */
export interface ConsentState {
  statement: { purpose: string; version: string; text: string };
  given: boolean;
}

export const CONSENT_LOAD_ERROR = 'We could not check your consent. Check your connection and try again.';
export const CONSENT_SAVE_ERROR = 'We could not record your consent. Please try again.';
export const CONSENT_STALE_ERROR = 'The consent wording has changed. Please read it again and confirm.';

/** Server-backed consent state for the wizard. Local state is only a cache of the last GET. */
@Injectable({ providedIn: 'root' })
export class ConsentService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);
  private readonly queue = inject(ExpenseQueueService);

  readonly state = signal<ConsentState | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly given = computed(() => this.state()?.given === true);

  /** Re-reads the server's verdict. Returns true only when the server says consent is given. */
  async refresh(): Promise<boolean> {
    this.loading.set(true);
    try {
      const s = await firstValueFrom(this.http.get<ConsentState>(`${this.base}/me/consent`));
      this.state.set(s);
      this.error.set('');
      return s.given === true;
    } catch {
      this.error.set(CONSENT_LOAD_ERROR);
      return false;
    } finally {
      this.loading.set(false);
    }
  }

  /** Records consent for the statement the user saw, then confirms with the server. */
  async grant(): Promise<boolean> {
    const s = this.state();
    if (!s || this.busy()) return false;
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(
        this.http.post(`${this.base}/me/consent`, { purpose: s.statement.purpose, statementVersion: s.statement.version }),
      );
    } catch (e) {
      const stale = e instanceof HttpErrorResponse && e.status === 400;
      if (stale) await this.refresh(); // pick up the current wording
      this.error.set(stale ? CONSENT_STALE_ERROR : CONSENT_SAVE_ERROR);
      this.busy.set(false);
      return false;
    }
    const ok = await this.refresh();
    this.busy.set(false);
    if (ok) {
      if (this.queue.consentNeeded()) await this.queue.onConsentGranted();
    } else if (!this.error()) {
      this.error.set(CONSENT_SAVE_ERROR);
    }
    return ok;
  }
}
