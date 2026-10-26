import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';

/**
 * Round 44px button at the top right of a screen header; opens More (settings, plan setup, sign out).
 *   <fp-page-header title="Home"><fp-profile-link fpTrailing /></fp-page-header>
 * It shows a generic person glyph, never a name or initial: nothing user-specific is hard-coded.
 */
@Component({
  selector: 'fp-profile-link',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a href="/more" aria-label="More" (click)="go($event)">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><circle cx="12" cy="8" r="4"></circle><path d="M4 21a8 8 0 0 1 16 0"></path></svg>
    </a>
  `,
  styles: `
    :host { display: block; flex: none; }
    a { display: flex; align-items: center; justify-content: center; width: var(--fp-target); height: var(--fp-target); border-radius: var(--fp-radius-pill); border: 1px solid var(--fp-border-strong); background: var(--fp-surface); color: var(--fp-text); }
  `,
})
export class FpProfileLinkComponent {
  private readonly router = inject(Router);

  /** Plain anchor (works in any screen without router-link plumbing); modified clicks keep the browser default. */
  go(e: MouseEvent): void {
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    void this.router.navigateByUrl('/more');
  }
}
