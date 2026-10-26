import { TestBed } from '@angular/core/testing';
import { FpProgressComponent } from './fp-progress.component';
import { setInputs } from './ui-testing';

describe('FpProgressComponent', () => {
  function make(inputs: Record<string, unknown>) {
    const f = TestBed.createComponent(FpProgressComponent);
    setInputs(f, { label: 'Usage', value: 20, valueText: 'Twenty of one hundred', ...inputs });
    const q = (s: string) => f.nativeElement.querySelector(s) as HTMLElement;
    return { f, q };
  }

  it('is a meter with now / min / max / valuetext and a name', () => {
    const { q } = make({ value: 20, max: 80 });
    const m = q('[role=meter]');
    expect(m.getAttribute('aria-label')).toBe('Usage');
    expect(m.getAttribute('aria-valuenow')).toBe('20');
    expect(m.getAttribute('aria-valuemin')).toBe('0');
    expect(m.getAttribute('aria-valuemax')).toBe('80');
    expect(m.getAttribute('aria-valuetext')).toBe('Twenty of one hundred');
  });

  it('always shows the text equivalent as visible text', () => {
    const { q } = make({});
    expect(q('.cap').textContent).toBe('Twenty of one hundred');
  });

  it('fill width is value / max, clamped to the track', () => {
    expect(make({ value: 25, max: 50 }).q('.fill').style.width).toBe('50%');
    expect(make({ value: 500, max: 50 }).q('.fill').style.width).toBe('100%');
    expect(make({ value: -5, max: 50 }).q('.fill').style.width).toBe('0%');
    expect(make({ value: 5, max: 0 }).q('.fill').style.width).toBe('0%');
  });

  it('shows a limit marker with a visible label only when a limit is given', () => {
    const none = make({});
    expect(none.q('.marker')).toBeNull();
    expect(none.q('.limit')).toBeNull();
    const withLimit = make({ limit: 40, max: 100, limitLabel: 'Cap' });
    expect(withLimit.q('.marker').style.left).toBe('40%');
    expect(withLimit.q('.limit').textContent?.trim()).toBe('Cap');
  });

  it('tone picks the bar colour token; the caller decides the tone', () => {
    const { f, q } = make({ tone: 'accent' });
    const accent = getComputedStyle(q('.fill')).backgroundColor;
    setInputs(f, { tone: 'breach' });
    const breach = getComputedStyle(q('.fill')).backgroundColor;
    setInputs(f, { tone: 'warn' });
    const warn = getComputedStyle(q('.fill')).backgroundColor;
    expect(new Set([accent, breach, warn]).size).toBe(3);
  });
});
