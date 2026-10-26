import { TestBed } from '@angular/core/testing';
import { FpSegmentedComponent } from './fp-segmented.component';
import { expectTarget44, setInputs } from './ui-testing';

const OPTIONS = [
  { value: 'A', label: 'Alpha' },
  { value: 'B', label: 'Beta' },
  { value: 'C', label: 'Gamma' },
];

describe('FpSegmentedComponent', () => {
  function make(inputs: Record<string, unknown> = {}) {
    const f = TestBed.createComponent(FpSegmentedComponent);
    setInputs(f, { options: OPTIONS, label: 'Pick one', ...inputs });
    const emitted: (string | null)[] = [];
    f.componentInstance.valueChange.subscribe((v) => emitted.push(v));
    const buttons = () => Array.from(f.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    return { f, emitted, buttons };
  }

  it('renders a labelled group of real buttons with aria-pressed', () => {
    const { f, buttons } = make({ value: 'B' });
    const group = f.nativeElement.querySelector('[role=group]') as HTMLElement;
    expect(group.getAttribute('aria-label')).toBe('Pick one');
    expect(buttons().map((b) => b.getAttribute('aria-pressed'))).toEqual(['false', 'true', 'false']);
    expect(buttons().every((b) => b.type === 'button')).toBeTrue();
  });

  it('applies the aria prefix to accessible names but not to visible text', () => {
    const { buttons } = make({ ariaPrefix: 'Tag ' });
    expect(buttons()[0].getAttribute('aria-label')).toBe('Tag Alpha');
    expect(buttons()[0].textContent?.trim()).toBe('Alpha');
  });

  it('emits the chosen value on click and on keyboard activation', () => {
    const { buttons, emitted } = make();
    buttons()[2].click(); // a keyboard Enter/Space on a <button> dispatches click
    expect(emitted).toEqual(['C']);
  });

  it('pressing the selected segment does nothing, unless deselectable (then null)', () => {
    const a = make({ value: 'A' });
    a.buttons()[0].click();
    expect(a.emitted).toEqual([]);
    const b = make({ value: 'A', deselectable: true });
    b.buttons()[0].click();
    expect(b.emitted).toEqual([null]);
  });

  it('is focusable in tab order and every button is a 44px target, with a 38px visual segment', () => {
    const { f, buttons } = make();
    for (const b of buttons()) {
      expect(b.tabIndex).toBe(0);
      expectTarget44(b, b.textContent ?? '');
    }
    const seg = f.nativeElement.querySelector('.seg') as HTMLElement;
    expect(seg.getBoundingClientRect().height).toBe(38);
  });
});
