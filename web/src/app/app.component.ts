import { ChangeDetectionStrategy, Component, computed, effect, inject, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AUTH_PATHS } from './app.routes';
import { AuthService } from './auth/auth.service';
import { AuthSessionStore } from './auth/session.store';
import { ThemeService } from './theme.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  readonly themeService = inject(ThemeService);
  private readonly router = inject(Router);
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  /** Sign-in and the OAuth callback show only the brand and theme toggle. */
  readonly chrome = computed(() => {
    const path = this.url().split(/[?#]/)[0];
    return !AUTH_PATHS.includes(path);
  });
  private readonly session = inject(AuthSessionStore);
  private readonly auth = inject(AuthService);
  private wasSignedIn = this.session.isSignedIn();

  constructor() {
    // Signed-in -> signed-out without this tab asking (other tab signed out, refresh rejected): go to /sign-in,
    // remembering where the user was. Only the transition counts; starting signed out is the guard's job.
    effect(
      () => {
        const now = this.session.isSignedIn();
        untracked(() => {
          if (now) this.auth.sessionPresent();
          else if (this.wasSignedIn) this.auth.sessionLost(this.router.url);
          this.wasSignedIn = now;
        });
      },
      { allowSignalWrites: true },
    );
  }

  readonly nav = [
    { path: '/home', label: 'Home', icon: 'M3 11l9-8 9 8v10h-6v-6H9v6H3z' },
    { path: '/spending', label: 'Spending', icon: 'M4 20V10h4v10zm6 0V4h4v16zm6 0v-7h4v7z' },
    { path: '/debt', label: 'Debt', icon: 'M3 6h18v12H3zm3 3v6h12V9z' },
    { path: '/goals', label: 'Goals', icon: 'M12 2a10 10 0 100 20 10 10 0 000-20zm0 5a5 5 0 110 10 5 5 0 010-10zm0 3a2 2 0 100 4 2 2 0 000-4z' },
    { path: '/more', label: 'More', icon: 'M5 10a2 2 0 100 4 2 2 0 000-4zm7 0a2 2 0 100 4 2 2 0 000-4zm7 0a2 2 0 100 4 2 2 0 000-4z' },
  ];
}
