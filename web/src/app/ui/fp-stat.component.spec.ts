import { TestBed } from '@angular/core/testing';
import { FpStatComponent } from './fp-stat.component';
import { setInputs } from './ui-testing';

describe('FpStatComponent', () => {
  function make(inputs: Record<string, unknown>) {
    const f = TestBed.createComponent(FpStatComponent);
    setInputs(f, { label: 'Spent', value: '1,234', ...inputs });
    return f;
  }

  it('shows the label and the already-formatted value verbatim (no maths, no reformatting)', () => {
    const el: HTMLElement = make({ value: '₹56,789' }).nativeElement;
    expect(el.querySelector('.fp-label')?.textContent).toBe('Spent');
    expect(el.querySelector('.value')?.textContent?.trim()).toBe('₹56,789');
  });

  it('uses the number font with the label style', () => {
    const el: HTMLElement = make({}).nativeElement;
    expect(el.querySelector('.value')?.classList).toContain('fp-num');
    expect(getComputedStyle(el.querySelector('.fp-label')!).letterSpacing).not.toBe('normal');
  });

  it('renders the hint only when given', () => {
    const f = make({});
    expect(f.nativeElement.querySelector('.hint')).toBeNull();
    setInputs(f, { hint: 'of budget' });
    expect(f.nativeElement.querySelector('.hint')?.textContent).toBe('of budget');
  });

  it('warn and breach tones add screen-reader words so colour is not the only signal', () => {
    const f = make({ tone: 'warn' });
    expect(f.nativeElement.querySelector('.sr-only')?.textContent).toContain('warning');
    setInputs(f, { tone: 'breach' });
    expect(f.nativeElement.querySelector('.sr-only')?.textContent).toContain('over the limit');
    setInputs(f, { tone: 'accent' });
    expect(f.nativeElement.querySelector('.sr-only')).toBeNull();
  });

  it('tone colours the value with the matching token and neutral keeps the text colour', () => {
    const f = make({ tone: 'neutral' });
    const value = f.nativeElement.querySelector('.value') as HTMLElement;
    const neutral = getComputedStyle(value).color;
    setInputs(f, { tone: 'breach' });
    expect(getComputedStyle(value).color).not.toBe(neutral);
    expect(f.nativeElement.getAttribute('data-tone')).toBe('breach');
  });
});
