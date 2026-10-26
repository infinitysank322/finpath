import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type FpTone = 'neutral' | 'accent' | 'warn' | 'breach';

/**
 * A labelled figure: small uppercase label, big number, optional hint.
 *   <fp-stat label="Spent" [value]="spent | paiseInr" hint="of budget" tone="warn" size="lg" />
 * `value` and `hint` are ALREADY-FORMATTED text (callers use PaiseInrPipe); this component does no money maths.
 * Tone also adds screen-reader words for warn / breach so colour is never the only signal.
 */
@Component({
  selector: 'fp-stat',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-tone]': 'tone()', '[attr.data-size]': 'size()' },
  template: `
    <span class="fp-label">{{ label() }}</span>
    <span class="value fp-num">{{ value() }}@if (toneWord(); as w) {<span class="sr-only"> ({{ w }})</span>}</span>
    @if (hint()) {
      <span class="hint">{{ hint() }}</span>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--fp-space-1); min-width: 0; }
    .value { font-size: var(--fp-fs-xl); font-weight: 700; line-height: 1.1; letter-spacing: var(--fp-num-tracking); overflow-wrap: anywhere; }
    :host([data-size='lg']) .value { font-size: var(--fp-fs-2xl); line-height: 1; }
    :host([data-size='xl']) .value { font-size: 3.125rem; line-height: 1; }
    .hint { font-size: var(--fp-fs-xs); color: var(--fp-text-muted); }
    :host([data-tone='accent']) .value { color: var(--fp-accent-text); }
    :host([data-tone='warn']) .value { color: var(--fp-warn-text); }
    :host([data-tone='breach']) .value { color: var(--fp-breach-text); }
  `,
})
export class FpStatComponent {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly hint = input<string>('');
  readonly tone = input<FpTone>('neutral');
  readonly size = input<'md' | 'lg' | 'xl'>('md');
  protected readonly toneWord = computed(() =>
    this.tone() === 'warn' ? 'warning' : this.tone() === 'breach' ? 'over the limit' : '',
  );
}
