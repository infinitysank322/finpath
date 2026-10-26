import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Card surface.
 *   <fp-card heading="Monthly EMIs" variant="hero"> ...content... </fp-card>
 * - variant 'default' is the flat surface; 'hero' adds the gradient, inset highlight and drop shadow.
 * - `heading` renders a section label (h2). For anything richer project an element with the `fpCardHeading` attribute.
 * - Children are laid out as a column with a 12px gap; the card does no number formatting or maths.
 */
@Component({
  selector: 'fp-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.hero]': "variant() === 'hero'" },
  template: `
    @if (heading()) {
      <h2 class="fp-label">{{ heading() }}</h2>
    }
    <ng-content select="[fpCardHeading]" />
    <ng-content />
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--fp-space-3); min-width: 0; padding: var(--fp-space-4); background: var(--fp-surface); border: 1px solid var(--fp-card-border); border-radius: var(--fp-radius-lg); }
    :host(.hero) { padding: var(--fp-space-5); background: var(--fp-card-gradient); border-radius: var(--fp-radius-xl); box-shadow: var(--fp-shadow-card); }
  `,
})
export class FpCardComponent {
  readonly variant = input<'default' | 'hero'>('default');
  readonly heading = input<string>('');
}
