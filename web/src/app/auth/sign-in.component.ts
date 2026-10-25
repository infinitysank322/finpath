import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-sign-in',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="auth-card" aria-labelledby="signin-h">
      <h1 id="signin-h">Sign in to FinPath</h1>
      <p class="muted">
        Plan each pay cycle, log spending in seconds and see your way out of debt. Your plan stays private to your
        account.
      </p>
      <div role="alert" class="msg">{{ message() }}</div>
      <button type="button" class="primary" (click)="go()" [disabled]="busy()" [attr.aria-busy]="busy()">
        Continue with Google
      </button>
    </section>
  `,
  styleUrl: './auth.css',
})
export class SignInComponent {
  private readonly auth = inject(AuthService);
  readonly busy = signal(false);
  readonly message = signal('');

  async go(): Promise<void> {
    if (this.busy()) return; // a double tap must not start two round trips
    this.busy.set(true);
    this.message.set('');
    const started = await this.auth.startSignIn();
    if (!started) {
      this.busy.set(false);
      this.message.set('Sign-in needs browser storage and a secure connection. Turn off private browsing or blocked storage, then try again.');
    }
  }
}
