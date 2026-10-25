import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ExpenseQueueService } from '../expense/expense-queue.service';

@Component({
  selector: 'app-more',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>More</h1>
    <nav aria-label="Plan setup">
      <ul>
        <li><a routerLink="/more/income">Income sources</a></li>
        <li><a routerLink="/more/recurring">Recurring bills</a></li>
        <li><a routerLink="/spending/budget">Category budgets</a></li>
        <li><a routerLink="/goals/fi-planner">FI planner</a></li>
        <li><a routerLink="/more/allocation">Cycle allocation</a></li>
      </ul>
    </nav>
    <section aria-labelledby="acct-h">
      <h2 id="acct-h">Account</h2>
      @if (unsent() !== null) {
        <p role="alert" class="warn" data-f="unsent-warning">
          {{ unsent() }} {{ unsent() === 1 ? 'expense has' : 'expenses have' }} not been sent yet. Sign out anyway keeps
          them on this device until you sign in with this account again; they are deleted if someone else signs in
          first. On a shared device, choose
          Sign out and discard: they are deleted and cannot be recovered.
        </p>
        <div class="row">
          <button type="button" class="btn" (click)="confirm()">Sign out anyway</button>
          <button type="button" class="btn" (click)="discard()" data-f="discard">Sign out and discard</button>
          <button type="button" class="btn" (click)="unsent.set(null)">Cancel</button>
        </div>
      } @else {
        <button type="button" class="btn" (click)="signOut()">Sign out</button>
      }
    </section>
  `,
  styles: [
    `h1 { margin: 0 0 .5rem; font-size: 1.5rem; }
     h2 { margin: 1.5rem 0 .5rem; font-size: 1.125rem; }
     ul { list-style: none; margin: 0; padding: 0; }
     li { margin-bottom: .5rem; }
     a { display: block; min-height: 44px; padding: .625rem .75rem; box-sizing: border-box; background: var(--fp-surface); border: 1px solid var(--fp-border); border-radius: 8px; color: var(--fp-primary-text); }
     .warn { margin: 0 0 .75rem; padding: .75rem; border-radius: 8px; background: var(--fp-warn-bg); color: var(--fp-warn-text); }
     .row { display: flex; flex-wrap: wrap; gap: .5rem; }
     .btn { min-height: 44px; padding: 0 1rem; border-radius: 22px; border: 1px solid var(--fp-border-strong); background: transparent; color: var(--fp-text); font: inherit; cursor: pointer; }`,
  ],
})
export class MoreComponent {
  private readonly auth = inject(AuthService);
  private readonly queue = inject(ExpenseQueueService);
  /** Count shown in the sign-out warning; null = no warning open. */
  readonly unsent = signal<number | null>(null);

  signOut(): void {
    this.queue.reload();
    const n = this.queue.items().length;
    if (n > 0) this.unsent.set(n);
    else void this.confirm();
  }

  /** Shared-device option: unsent expenses are deleted, leaving nothing queued for this user. */
  async discard(): Promise<void> {
    this.unsent.set(null);
    await this.auth.signOut({ discardQueue: true });
    this.queue.reload();
  }

  /** The signed-out user's queue is kept under their own key; it is not deleted. */
  async confirm(): Promise<void> {
    this.unsent.set(null);
    await this.auth.signOut();
    this.queue.reload();
  }
}
