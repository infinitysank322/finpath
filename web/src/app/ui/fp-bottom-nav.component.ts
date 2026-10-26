import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map } from 'rxjs';

interface NavItem {
  path: string;
  label: string;
  /** SVG markup children (stroke icons on a 24 grid). Static literals, never user data. */
  icon: string;
}

/** The four destinations; the central "+" is rendered between Spend and Debt. */
export const NAV_ITEMS: NavItem[] = [
  { path: '/home', label: 'Home', icon: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z' },
  { path: '/spending', label: 'Spend', icon: 'M6 6h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3zM3 10h18M16 15h2' },
  { path: '/debt', label: 'Debt', icon: 'm12 3 9 5-9 5-9-5zM3 13l9 5 9-5' },
  { path: '/goals', label: 'Goals', icon: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM12 11a1 1 0 1 0 0 2 1 1 0 0 0 0-2z' },
];

/** Where the central + button goes. It is a shortcut, never "current page". */
export const ADD_PATH = '/spending/add';

/**
 * Fixed bottom navigation: Home, Spend, [+ Add expense], Debt, Goals. Safe-area aware (home-indicator inset).
 * Pages must leave room for it: use padding-bottom: var(--fp-nav-h) (the app shell already does for <main>).
 * The active destination gets aria-current="page" and an accent icon + bold label. A destination is active when the URL
 * equals its path or is below it, except /spending/add, which belongs to the + button and so does not light up Spend.
 * Every target is >= 44px; labels are always visible.
 */
@Component({
  selector: 'fp-bottom-nav',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav aria-label="Primary">
      @for (item of items; track item.path; let i = $index) {
        @if (i === 2) {
          <a class="add" [routerLink]="addPath" aria-label="Add expense">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true" focusable="false"><path d="M12 5v14M5 12h14"></path></svg>
          </a>
        }
        <a class="item" [routerLink]="item.path" [class.active]="isActive(item.path)" [attr.aria-current]="isActive(item.path) ? 'page' : null">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path [attr.d]="item.icon"></path></svg>
          <span>{{ item.label }}</span>
        </a>
      }
    </nav>
  `,
  styles: `
    :host { position: fixed; left: 0; right: 0; bottom: 0; z-index: 20; }
    nav { display: flex; align-items: center; justify-content: space-around; height: var(--fp-nav-h); padding: 0 6px env(safe-area-inset-bottom, 0px); background: var(--fp-nav-bg); border-top: 1px solid var(--fp-nav-border); }
    .item { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; min-width: 56px; max-width: 72px; flex: 1; height: 52px; color: var(--fp-text-muted); text-decoration: none; font-size: var(--fp-fs-label); font-weight: 600; }
    .item.active { color: var(--fp-accent-text); }
    .item.active span { color: var(--fp-text); font-weight: 700; }
    .add { display: flex; align-items: center; justify-content: center; flex: none; width: 58px; height: 58px; margin-top: -18px; border-radius: 20px; background: var(--fp-accent); color: var(--fp-on-accent); border: 1px solid var(--fp-accent-edge); box-shadow: var(--fp-shadow-fab); }
  `,
})
export class FpBottomNavComponent {
  private readonly router = inject(Router);
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  readonly items = NAV_ITEMS;
  readonly addPath = ADD_PATH;

  isActive(path: string): boolean {
    const p = this.url().split(/[?#]/)[0];
    if (p === ADD_PATH || p.startsWith(ADD_PATH + '/')) return false;
    return p === path || p.startsWith(path + '/');
  }
}
