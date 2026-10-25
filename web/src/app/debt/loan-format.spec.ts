import { dmy, pctToRate, rateToPctText } from './loan-format';

describe('loan-format', () => {
  it('converts percent text to an API fraction without floats', () => {
    expect(pctToRate('8.5')).toBe('0.085');
    expect(pctToRate('15')).toBe('0.15');
    expect(pctToRate('0.5')).toBe('0.005');
    expect(pctToRate('8.5%')).toBe('0.085');
  });
  it('rejects malformed or out-of-range percentages', () => {
    for (const t of ['', 'abc', '100', '-1', '1.123456789']) expect(pctToRate(t)).toBeNull();
  });
  it('shows an API fraction as a percentage', () => {
    expect(rateToPctText('0.0800000000')).toBe('8%');
    expect(rateToPctText('0.085')).toBe('8.5%');
    expect(rateToPctText('0.2231')).toBe('22.31%');
    expect(rateToPctText('0.005')).toBe('0.5%');
  });
  it('formats dates without month names', () => {
    expect(dmy('2031-10-25')).toBe('25/10/2031');
  });
});
