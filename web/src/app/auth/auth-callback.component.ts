import { HttpErrorResponse } from '@angular/common/http';
import { Location } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { ExpenseQueueService } from '../expense/expense-queue.service';
import { AuthApiService } from './auth-api.service';
import { AuthService } from './auth.service';
import { DEFAULT_RETURN_PATH, safeReturnPath } from './return-path';
import { AuthSessionStore, isTokenResponse } from './session.store';

export type CallbackError = 'email_not_verified' | 'login_failed' | 'expired' | 'invalid_grant' | 'network';

/** Fixed strings keyed by code. A code, token or email is never shown. */
export const CALLBACK_MESSAGES: Record<CallbackError, string> = {
  email_not_verified: "Your Google account's email address isn't verified yet. Verify it with Google, then try again.",
  login_failed: "Google sign-in didn't finish. Please try again.",
  expired: 'Sign-in expired, try again.',
  invalid_grant: 'That sign-in has already been used or has expired. Please try again.',
  network: "Couldn't reach FinPath. Check your connection and try again.",
};

@Component({
  selector: 'app-auth-callback',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="auth-card" aria-labelledby="cb-h">
      @if (error(); as e) {
        <h1 id="cb-h">Sign-in didn't finish</h1>
        <p role="alert" class="msg">{{ messages[e] }}</p>
        <button type="button" class="primary" (click)="retry()" [disabled]="busy()">Try again</button>
      } @else {
        <h1 id="cb-h">Signing you in</h1>
        <p role="status" aria-live="polite" class="muted">One moment...</p>
      }
    </section>
  `,
  styleUrl: './auth.css',
})
export class AuthCallbackComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly api = inject(AuthApiService);
  private readonly store = inject(AuthSessionStore);
  private readonly queue = inject(ExpenseQueueService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly destroyRef = inject(DestroyRef);

  readonly messages = CALLBACK_MESSAGES;
  readonly error = signal<CallbackError | null>(null);
  readonly busy = signal(false);
  private returnTo = DEFAULT_RETURN_PATH;

  ngOnInit(): void {
    // 1. Take the round-trip entry before anything else: it can never be used twice.
    const entry = this.auth.takeSignInEntry();
    const q = this.route.snapshot.queryParamMap;
    const code = q.get('code');
    const state = q.get('state');
    const err = q.get('error');
    // 2. The one-time code leaves the address bar (and history) before any request is made.
    this.location.replaceState('/auth/callback');
    if (entry) this.returnTo = safeReturnPath(entry.returnTo);

    // 3. Anything off: no API call.
    if (err !== null) return this.error.set(err === 'email_not_verified' ? 'email_not_verified' : 'login_failed');
    if (!entry || !state || state !== entry.state || !code) return this.error.set('expired');

    // 4. Exchange the code with the verifier that never left this device.
    this.api
      .token(code, entry.verifier)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (body) => {
          if (!isTokenResponse(body)) return this.error.set('login_failed');
          this.store.save(body);
          this.queue.reload(); // the queue key is per user: switch to this user's queue
          void this.queue.flush(); // send anything queued while signed out or offline
          void this.router.navigateByUrl(this.returnTo, { replaceUrl: true });
        },
        error: (e: unknown) =>
          this.error.set(e instanceof HttpErrorResponse && e.status === 401 ? 'invalid_grant' : 'network'),
      });
  }

  async retry(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    if (!(await this.auth.startSignIn(this.returnTo))) {
      this.busy.set(false);
      void this.router.navigateByUrl('/sign-in');
    }
  }
}
