import { ordinalDay, dmy, pctToRate, rateToPctText } from './loan-format';

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
  it('writes day-of-month ordinals', () => {
    expect([1, 2, 3, 4, 5, 11, 12, 13, 21, 22, 23, 31].map(ordinalDay)).toEqual(
      ['1st', '2nd', '3rd', '4th', '5th', '11th', '12th', '13th', '21st', '22nd', '23rd', '31st']);
  });
});
