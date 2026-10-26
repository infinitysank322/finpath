import { TestBed } from '@angular/core/testing';
import { pressKey } from '../expense/amount-entry';
import { FpKeypadComponent, KeypadKey } from './fp-keypad.component';
import { expectTarget44 } from './ui-testing';

describe('FpKeypadComponent', () => {
  function make() {
    const f = TestBed.createComponent(FpKeypadComponent);
    f.detectChanges();
    const pressed: KeypadKey[] = [];
    f.componentInstance.keyPress.subscribe((k) => pressed.push(k));
    const buttons = () => Array.from(f.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    return { f, pressed, buttons };
  }

  it('is a labelled group of 12 buttons in 3x4 order', () => {
    const { f, buttons } = make();
    expect(f.nativeElement.querySelector('[role=group]').getAttribute('aria-label')).toBe('Amount keypad');
    expect(buttons().length).toBe(12);
    expect(buttons().map((b) => b.getAttribute('aria-label'))).toEqual([
      'Digit 1', 'Digit 2', 'Digit 3', 'Digit 4', 'Digit 5', 'Digit 6', 'Digit 7', 'Digit 8', 'Digit 9',
      'Decimal point', 'Digit 0', 'Delete last digit',
    ]);
    expect(getComputedStyle(f.nativeElement.querySelector('.pad')).gridTemplateColumns.split(' ').length).toBe(3);
  });

  it('emits the key value for each press (digits, ".", "back") and nothing else', () => {
    const { buttons, pressed } = make();
    buttons()[4].click();
    buttons()[9].click();
    buttons()[10].click();
    buttons()[11].click();
    expect(pressed).toEqual(['5', '.', '0', 'back']);
  });

  it('emitted keys drive the existing amount-entry logic unchanged', () => {
    const { buttons, pressed } = make();
    [0, 1, 9, 2, 11].forEach((i) => buttons()[i].click()); // 1 2 . 3 back
    expect(pressed.reduce((t, k) => pressKey(t, k), '')).toBe('12.');
  });

  it('every key is a real, tabbable button of at least 44px', () => {
    const { buttons } = make();
    for (const b of buttons()) {
      expect(b.tagName).toBe('BUTTON');
      expect(b.type).toBe('button');
      expect(b.tabIndex).toBe(0);
      expectTarget44(b, b.getAttribute('aria-label') ?? '');
    }
  });
});
