import { ChangeDetectionStrategy, Component, computed, effect, inject, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AUTH_PATHS } from './app.routes';
import { AuthService } from './auth/auth.service';
import { LocalDataService } from './auth/local-data.service';
import { AuthSessionStore } from './auth/session.store';
import { FpBottomNavComponent } from './ui/fp-bottom-nav.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, FpBottomNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly router = inject(Router);
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  /** Sign-in and the OAuth callback show no bottom nav. */
  readonly chrome = computed(() => {
    const path = this.url().split(/[?#]/)[0];
    return !AUTH_PATHS.includes(path);
  });
  private readonly session = inject(AuthSessionStore);
  private readonly auth = inject(AuthService);
  private readonly local = inject(LocalDataService);
  private wasSignedIn = this.session.isSignedIn();

  constructor() {
    // Signed-in -> signed-out without this tab asking (other tab signed out, refresh rejected): go to /sign-in,
    // remembering where the user was. Only the transition counts; starting signed out is the guard's job.
    effect(
      () => {
        const now = this.session.isSignedIn();
        untracked(() => {
          if (now) {
            this.auth.sessionPresent();
            this.local.purgeOrphans(); // other users' leftover queues: one session per device
          }
          else if (this.wasSignedIn) this.auth.sessionLost(this.router.url);
          this.wasSignedIn = now;
        });
      },
      { allowSignalWrites: true },
    );
  }
}
