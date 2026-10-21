import { PaiseInrPipe } from './paise-inr.pipe';

describe('PaiseInrPipe', () => {
  const pipe = new PaiseInrPipe();

  it('abbreviates lakhs', () => expect(pipe.transform(501600000)).toBe('₹50.16 L'));
  it('formats small amounts in rupees', () => expect(pipe.transform(1900)).toBe('₹19'));
  it('groups lakh-style below the abbreviation threshold', () =>
    expect(pipe.transform(12700000)).toBe('₹1,27,000'));
  it('abbreviates crores', () => expect(pipe.transform(3840000000)).toBe('₹3.84 Cr'));
  it('drops a redundant fraction', () => expect(pipe.transform(1000000000)).toBe('₹1 Cr'));
  it('picks the unit from the rounded value', () => {
    expect(pipe.transform(99999950)).toBe('₹10 L');
    expect(pipe.transform(999999950)).toBe('₹1 Cr');
    expect(pipe.transform(9999999999)).toBe('₹10 Cr');
    expect(pipe.transform(99999949)).toBe('₹9,99,999');
  });
  it('keeps the sign on abbreviated amounts', () => expect(pipe.transform(-501600000)).toBe('-₹50.16 L'));
  it('handles zero and negatives', () => {
    expect(pipe.transform(0)).toBe('₹0');
    expect(pipe.transform(-12700000)).toBe('-₹1,27,000');
  });
  it('returns empty for null/undefined', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
  });
});
