import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Per-screen header (the app shell has no top bar any more).
 *   <fp-page-header eyebrow="Overview" title="Home"><button fpTrailing>...</button></fp-page-header>
 * - `title` is the page's single <h1>, in the number font. `eyebrow` is an optional section label above it.
 * - Slots: [fpLeading] before the title (e.g. a close button), [fpTrailing] after it. Anything projected must be
 *   >= 44px if interactive.
 * - `centered` centres the title between the slots; give both sides a 44px element so it stays centred.
 */
@Component({
  selector: 'fp-page-header',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.centered]': 'centered()' },
  template: `
    <ng-content select="[fpLeading]" />
    <div class="main">
      @if (eyebrow()) {
        <span class="fp-label">{{ eyebrow() }}</span>
      }
      <h1 class="fp-num">{{ title() }}</h1>
    </div>
    <ng-content select="[fpTrailing]" />
  `,
  styles: `
    :host { display: flex; align-items: center; gap: var(--fp-space-3); min-width: 0; }
    .main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
    :host(.centered) .main { text-align: center; }
    h1 { margin: 0; font-size: var(--fp-fs-xl); font-weight: 700; letter-spacing: -0.01em; line-height: 1.15; overflow-wrap: anywhere; }
    :host(.centered) h1 { font-size: var(--fp-fs-lg); }
  `,
})
export class FpPageHeaderComponent {
  readonly title = input.required<string>();
  readonly eyebrow = input<string>('');
  readonly centered = input<boolean>(false);
}
