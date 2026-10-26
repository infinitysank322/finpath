import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { FpTone } from './fp-stat.component';

/**
 * Horizontal meter with an optional limit marker.
 *   <fp-progress label="EMI to income" [value]="x" [max]="m" [limit]="l" limitLabel="Limit"
 *                valueText="Above the limit" tone="warn" />
 * - `value`, `max`, `limit` are numbers supplied by the caller (from the API payload) in any common unit; they are only
 *   turned into bar WIDTHS here (value / max, clamped 0..1). Nothing is derived from money.
 * - `tone` is chosen by the caller from the API state (never computed here).
 * - role="meter" with aria-valuenow / min / max and aria-valuetext = `valueText`; `valueText` is ALSO shown as a visible
 *   caption, so there is always a text equivalent. The limit marker has a visible label (`limitLabel`).
 */
@Component({
  selector: 'fp-progress',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-tone]': 'tone()' },
  template: `
    <div class="meter" role="meter" [attr.aria-label]="label()" aria-valuemin="0"
      [attr.aria-valuemax]="max()" [attr.aria-valuenow]="value()" [attr.aria-valuetext]="valueText()">
      <div class="track"><div class="fill" [style.width.%]="fillPct()"></div></div>
      @if (limitPct() !== null) {
        <div class="marker" [style.left.%]="limitPct()" aria-hidden="true"></div>
      }
    </div>
    @if (limitPct() !== null) {
      <div class="limit" aria-hidden="true"><span [style.width.%]="limitPct()">{{ limitLabel() }}</span></div>
    }
    <span class="cap">{{ valueText() }}</span>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--fp-space-1); min-width: 0; --bar: var(--fp-accent-edge); }
    :host([data-tone='warn']) { --bar: var(--fp-warn-bar); }
    :host([data-tone='breach']) { --bar: var(--fp-breach-bar); }
    :host([data-tone='neutral']) { --bar: var(--fp-series-neutral); }
    .meter { position: relative; padding: 4px 0; }
    .track { height: 10px; border-radius: var(--fp-radius-pill); background: var(--fp-track); overflow: hidden; }
    .fill { height: 100%; border-radius: var(--fp-radius-pill); background: var(--bar); transition: width var(--fp-motion-base) var(--fp-ease); }
    .marker { position: absolute; top: 0; bottom: 0; width: 2px; margin-left: -1px; background: var(--fp-text); }
    .limit { display: flex; font-size: var(--fp-fs-label); color: var(--fp-text-muted); }
    .limit span { display: block; box-sizing: border-box; text-align: right; padding-right: 4px; white-space: nowrap; }
    .cap { font-size: var(--fp-fs-xs); color: var(--fp-text-muted); }
  `,
})
export class FpProgressComponent {
  readonly label = input.required<string>();
  readonly value = input.required<number>();
  readonly max = input<number>(100);
  readonly limit = input<number | null>(null);
  readonly limitLabel = input<string>('Limit');
  /** Caller-supplied sentence for assistive tech and the visible caption. */
  readonly valueText = input.required<string>();
  readonly tone = input<FpTone>('accent');
  protected readonly fillPct = computed(() => this.pct(this.value()));
  protected readonly limitPct = computed(() => (this.limit() === null ? null : this.pct(this.limit() as number)));

  private pct(n: number): number {
    const m = this.max();
    return m > 0 ? Math.min(100, Math.max(0, (n / m) * 100)) : 0;
  }
}
