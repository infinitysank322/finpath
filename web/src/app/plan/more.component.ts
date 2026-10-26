import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ExpenseQueueService } from '../expense/expense-queue.service';
import { InstallPromptService } from '../pwa/install-prompt.service';
import { Theme, ThemeService } from '../theme.service';
import { FpCardComponent } from '../ui/fp-card.component';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { FpSegmentedComponent } from '../ui/fp-segmented.component';

@Component({
  selector: 'app-more',
  standalone: true,
  imports: [RouterLink, FpPageHeaderComponent, FpCardComponent, FpSegmentedComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fp-page">
      <fp-page-header title="More" />
      <nav aria-label="Plan setup">
        <ul>
          <li><a class="fp-row" routerLink="/more/income">Income sources</a></li>
          <li><a class="fp-row" routerLink="/more/recurring">Recurring bills</a></li>
          <li><a class="fp-row" routerLink="/spending/budget">Category budgets</a></li>
          <li><a class="fp-row" routerLink="/goals/fi-planner">FI planner</a></li>
          <li><a class="fp-row" routerLink="/more/allocation">Cycle allocation</a></li>
          <li><a class="fp-row" routerLink="/more/checklist">Monthly checklist</a></li>
          <li><a class="fp-row" routerLink="/more/investments">Investments</a></li>
        </ul>
      </nav>
      @if (installer.canInstall()) {
        <button type="button" class="fp-pill install" data-f="install" (click)="installer.install()">Install FinPath on this device</button>
      }
      <section class="block">
        <span class="fp-label" aria-hidden="true">Appearance</span>
        <fp-segmented label="Theme" ariaPrefix="Theme " [options]="themes" [value]="themeService.theme()" (valueChange)="setTheme($event)" />
      </section>
      <section class="block" aria-labelledby="acct-h">
        <h2 id="acct-h" class="fp-label">Account</h2>
        @if (unsent() !== null) {
          <p role="alert" class="fp-warn" data-f="unsent-warning">
            {{ unsent() }} {{ unsent() === 1 ? 'expense has' : 'expenses have' }} not been sent yet. Sign out anyway keeps
            them on this device until you sign in with this account again; they are deleted if someone else signs in
            first. On a shared device, choose
            Sign out and discard: they are deleted and cannot be recovered.
          </p>
          <div class="row">
            <button type="button" class="fp-pill" (click)="confirm()">Sign out anyway</button>
            <button type="button" class="fp-pill" (click)="discard()" data-f="discard">Sign out and discard</button>
            <button type="button" class="fp-pill" (click)="unsent.set(null)">Cancel</button>
          </div>
        } @else {
          <button type="button" class="fp-pill signout" (click)="signOut()">Sign out</button>
        }
      </section>
    </div>
  `,
  styles: [
    `ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--fp-space-2); }
     a.fp-row { min-height: var(--fp-target); color: var(--fp-text); text-decoration: none; font-weight: 600; }
     .block { display: flex; flex-direction: column; gap: var(--fp-space-2); }
     .block p { margin: 0; }
     .row { display: flex; flex-wrap: wrap; gap: var(--fp-space-2); }
     .signout, .install { align-self: flex-start; }`,
  ],
})
export class MoreComponent {
  private readonly auth = inject(AuthService);
  private readonly queue = inject(ExpenseQueueService);
  readonly themeService = inject(ThemeService);
  readonly installer = inject(InstallPromptService);
  readonly themes = [
    { value: 'dark', label: 'Dark' },
    { value: 'light', label: 'Light' },
  ];
  /** Count shown in the sign-out warning; null = no warning open. */
  readonly unsent = signal<number | null>(null);

  setTheme(theme: string | null): void {
    if (theme === 'dark' || theme === 'light') this.themeService.set(theme as Theme);
  }

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
