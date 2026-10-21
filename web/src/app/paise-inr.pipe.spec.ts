import { PaiseInrPipe } from './paise-inr.pipe';

describe('PaiseInrPipe', () => {
  const pipe = new PaiseInrPipe();

  it('abbreviates lakhs', () => expect(pipe.transform(123400000)).toBe('₹12.34 L'));
  it('formats small amounts in rupees', () => expect(pipe.transform(1700)).toBe('₹17'));
  it('groups lakh-style below the abbreviation threshold', () =>
    expect(pipe.transform(9876500)).toBe('₹98,765'));
  it('abbreviates crores', () => expect(pipe.transform(2560000000)).toBe('₹2.56 Cr'));
  it('drops a redundant fraction', () => expect(pipe.transform(1000000000)).toBe('₹1 Cr'));
  it('picks the unit from the rounded value', () => {
    expect(pipe.transform(99999950)).toBe('₹10 L');
    expect(pipe.transform(999999950)).toBe('₹1 Cr');
    expect(pipe.transform(9999999999)).toBe('₹10 Cr');
    expect(pipe.transform(99999949)).toBe('₹9,99,999');
  });
  it('keeps the sign on abbreviated amounts', () => expect(pipe.transform(-123400000)).toBe('-₹12.34 L'));
  it('handles zero and negatives', () => {
    expect(pipe.transform(0)).toBe('₹0');
    expect(pipe.transform(-9876500)).toBe('-₹98,765');
  });
  it('returns empty for null/undefined', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
  });
});
