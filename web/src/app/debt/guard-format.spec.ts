import { barPercent, ratioPercentText, statusExplanation, statusIcon, statusLabel } from './guard-format';

describe('guard-format', () => {
  it('formats ratios to two decimals, half up', () => {
    expect(ratioPercentText('0.4987000000')).toBe('49.87%');
    expect(ratioPercentText('0.49865')).toBe('49.87%');
    expect(ratioPercentText('0.498649999')).toBe('49.86%');
    expect(ratioPercentText('0.4')).toBe('40.00%');
    expect(ratioPercentText('0')).toBe('0.00%');
    expect(ratioPercentText('1.2')).toBe('120.00%');
    expect(ratioPercentText(null)).toBe('n/a');
  });
  it('clamps the bar', () => {
    expect(barPercent('0.4987')).toBeCloseTo(49.87);
    expect(barPercent('2')).toBe(100);
    expect(barPercent(null)).toBe(0);
  });
  it('gives each status a non-colour cue', () => {
    expect(new Set(['OK', 'WARN', 'BLOCK'].map((s) => statusIcon(s as never))).size).toBe(3);
    expect(statusLabel('WARN')).toBe('Warning');
    expect(statusExplanation('BLOCK', '51.40%', 'adding')).toContain('Adding this loan');
    expect(statusExplanation('BLOCK', '51.40%')).not.toContain('would take');
    expect(statusExplanation('BLOCK', 'n/a')).toContain('No income');
  });
});
