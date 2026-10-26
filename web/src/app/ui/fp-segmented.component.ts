import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

export interface FpOption {
  value: string;
  label: string;
}

/**
 * Compact single-select toggle group (Need / Want / Waste, UPI / Card / Cash ...).
 *   <fp-segmented label="Paid by" ariaPrefix="Payment mode " [options]="opts" [value]="mode()" (valueChange)="mode.set($event)" />
 * - Each segment is a <button aria-pressed>; the group is role="group" with an aria-label (`label`).
 * - `ariaPrefix` is prepended to each option's accessible name ("Tag " + "Need"); omit for the plain label.
 * - `deselectable`: pressing the selected segment emits null (for optional choices).
 * - Visual height is 38px; the button box is 44px so the touch target meets WCAG 2.5.5.
 * Show the visible heading yourself with <span class="fp-label" aria-hidden="true">.
 */
@Component({
  selector: 'fp-segmented',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tray" role="group" [attr.aria-label]="label()">
      @for (o of options(); track o.value) {
        <button type="button" [attr.aria-pressed]="o.value === value()" [attr.aria-label]="ariaPrefix() + o.label" (click)="pick(o.value)">
          <span class="seg">{{ o.label }}</span>
        </button>
      }
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .tray { display: flex; flex-wrap: wrap; gap: 2px; padding: 2px; background: var(--fp-segmented-bg); border: 1px solid var(--fp-border-strong); border-radius: var(--fp-radius-md); }
    button { flex: 1 1 0; min-width: 0; min-height: var(--fp-target); padding: 0; border: 0; background: none; font: inherit; color: inherit; cursor: pointer; display: flex; align-items: center; }
    .seg { flex: 1; display: flex; align-items: center; justify-content: center; height: 38px; padding: 0 var(--fp-space-2); border-radius: var(--fp-radius-sm); font-size: var(--fp-fs-xs); font-weight: 700; color: var(--fp-text-muted); white-space: nowrap; }
    button[aria-pressed='true'] .seg { background: var(--fp-segmented-on-bg); color: var(--fp-segmented-on-text); }
  `,
})
export class FpSegmentedComponent {
  readonly options = input.required<FpOption[]>();
  readonly value = input<string | null>(null);
  readonly label = input.required<string>();
  readonly ariaPrefix = input<string>('');
  readonly deselectable = input<boolean>(false);
  readonly valueChange = output<string | null>();

  protected pick(v: string): void {
    if (v === this.value()) {
      if (this.deselectable()) this.valueChange.emit(null);
      return;
    }
    this.valueChange.emit(v);
  }
}
