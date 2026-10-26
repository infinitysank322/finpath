import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/** Keys in display order; the values are exactly what `pressKey` in expense/amount-entry.ts understands. */
export const KEYPAD_KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', 'back'] as const;
export type KeypadKey = (typeof KEYPAD_KEYS)[number];

/**
 * 3x4 digit pad. It only emits key presses; all amount logic stays in expense/amount-entry.ts (`pressKey`).
 *   <fp-keypad (keyPress)="amount.update(t => pressKey(t, $event))" />
 * Emits '0'..'9', '.', or 'back'. Buttons are real <button>s (Enter / Space work), 54px tall, with aria-labels
 * "Digit N", "Decimal point" and "Delete digit". The wrapper is role="group" labelled by `label`.
 */
@Component({
  selector: 'fp-keypad',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pad keypad" role="group" [attr.aria-label]="label()">
      @for (k of keys; track k) {
        <button type="button" [attr.aria-label]="name(k)" (click)="keyPress.emit(k)">
          @if (k === 'back') {
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M9 5h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-6-7z"></path><path d="m13 10 4 4m0-4-4 4"></path></svg>
          } @else {
            <span aria-hidden="true">{{ k }}</span>
          }
        </button>
      }
    </div>
  `,
  styles: `
    :host { display: block; }
    .pad { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--fp-space-2); }
    button { height: 54px; min-height: var(--fp-target); display: flex; align-items: center; justify-content: center; padding: 0; background: var(--fp-key-bg); color: var(--fp-text); border: 1px solid var(--fp-border-strong); border-radius: var(--fp-radius-md); font: 500 1.375rem var(--fp-font-num); cursor: pointer; touch-action: manipulation; }
    button:active { background: var(--fp-chip-bg-2); }
  `,
})
export class FpKeypadComponent {
  readonly label = input<string>('Amount keypad');
  readonly keyPress = output<KeypadKey>();
  protected readonly keys = KEYPAD_KEYS;
  protected name(k: KeypadKey): string {
    return k === 'back' ? 'Delete digit' : k === '.' ? 'Decimal point' : `Digit ${k}`;
  }
}
