import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FpOption } from './fp-segmented.component';

let nextId = 0;

/**
 * Single-select grid of chips (category picker).
 *   <fp-chip-grid label="Category" ariaPrefix="Category " [options]="opts" [value]="cat()" (valueChange)="cat.set($event)" />
 * - Native radio inputs under visible labels: the group is role="group" with an aria-label (`label`), arrow keys move
 *   and select, Space selects, and the selected state is exposed as `checked` (not by colour alone: selected chips also
 *   carry a check mark).
 * - Chips are 44px tall; `columns` sets the grid width (default 3).
 * The visible heading is the caller's: <span class="fp-label" aria-hidden="true">.
 */
@Component({
  selector: 'fp-chip-grid',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[style.--fp-cols]': 'columns()' },
  template: `
    <div class="grid" role="group" [attr.aria-label]="label()">
      @for (o of options(); track o.value) {
        <label class="chip">
          <input type="radio" [name]="name" [value]="o.value" [checked]="o.value === value()"
            [attr.aria-label]="ariaPrefix() + o.label" (change)="valueChange.emit(o.value)" />
          <span>@if (o.value === value()) {<span aria-hidden="true">&#10003; </span>}{{ o.label }}</span>
        </label>
      }
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .grid { display: grid; grid-template-columns: repeat(var(--fp-cols, 3), minmax(0, 1fr)); gap: var(--fp-space-2); }
    .chip { position: relative; display: flex; align-items: center; justify-content: center; min-height: var(--fp-target); height: 44px; padding: 0 var(--fp-space-1); background: var(--fp-surface); color: var(--fp-text-2); border: 1px solid var(--fp-border-strong); border-radius: var(--fp-radius-md); font-size: var(--fp-fs-sm); font-weight: 700; text-align: center; cursor: pointer; }
    input { position: absolute; inset: -1px; width: calc(100% + 2px); height: calc(100% + 2px); margin: 0; opacity: 0; cursor: pointer; }
    .chip:has(input:checked) { background: var(--fp-accent); color: var(--fp-on-accent); border-color: var(--fp-accent-edge); }
    .chip:has(input:focus-visible) { outline: 3px solid var(--fp-focus); outline-offset: 2px; }
  `,
})
export class FpChipGridComponent {
  readonly options = input.required<FpOption[]>();
  readonly value = input<string | null>(null);
  readonly label = input.required<string>();
  readonly ariaPrefix = input<string>('');
  readonly columns = input<number>(3);
  readonly valueChange = output<string>();
  protected readonly name = `fp-chips-${nextId++}`;
}
