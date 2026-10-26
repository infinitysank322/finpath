import { TestBed } from '@angular/core/testing';
import { FpChipGridComponent } from './fp-chip-grid.component';
import { expectTarget44, setInputs } from './ui-testing';

const OPTIONS = [
  { value: 'A', label: 'Alpha' },
  { value: 'B', label: 'Beta' },
  { value: 'C', label: 'Gamma' },
  { value: 'D', label: 'Delta' },
];

describe('FpChipGridComponent', () => {
  function make(inputs: Record<string, unknown> = {}) {
    const f = TestBed.createComponent(FpChipGridComponent);
    setInputs(f, { options: OPTIONS, label: 'Pick', ariaPrefix: 'Choice ', ...inputs });
    const emitted: string[] = [];
    f.componentInstance.valueChange.subscribe((v) => emitted.push(v));
    const radios = () => Array.from(f.nativeElement.querySelectorAll('input[type=radio]')) as HTMLInputElement[];
    return { f, emitted, radios };
  }

  it('is a labelled group of radios sharing one name, with accessible names', () => {
    const { f, radios } = make({ value: 'B' });
    expect(f.nativeElement.querySelector('[role=group]').getAttribute('aria-label')).toBe('Pick');
    expect(radios().map((r) => r.getAttribute('aria-label'))).toEqual(['Choice Alpha', 'Choice Beta', 'Choice Gamma', 'Choice Delta']);
    expect(new Set(radios().map((r) => r.name)).size).toBe(1);
    expect(radios().map((r) => r.checked)).toEqual([false, true, false, false]);
  });

  it('two grids on one page do not share a radio group name', () => {
    const a = make();
    const b = make();
    expect(a.radios()[0].name).not.toBe(b.radios()[0].name);
  });

  it('emits on selection; selection is also marked with a check mark, not colour alone', () => {
    const { f, radios, emitted } = make();
    radios()[3].click();
    expect(emitted).toEqual(['D']);
    setInputs(f, { value: 'D' });
    expect(f.nativeElement.querySelectorAll('.chip')[3].textContent).toContain('✓');
    expect(f.nativeElement.querySelectorAll('.chip')[0].textContent).not.toContain('✓');
  });

  it('is keyboard operable (native radios are tabbable) and each chip is a 44px target', () => {
    const { radios } = make();
    for (const r of radios()) {
      expect(r.tabIndex).toBe(0);
      expectTarget44(r, r.getAttribute('aria-label') ?? '');
    }
  });

  it('lays chips out in the requested number of columns', () => {
    const { f } = make({ columns: 2 });
    const cols = getComputedStyle(f.nativeElement.querySelector('.grid')).gridTemplateColumns.split(' ');
    expect(cols.length).toBe(2);
  });
});
