import { errorMessage, paiseToRupeesText, rupeesTextToPaise, shortDate } from './plan-format';

describe('plan-format', () => {
  it('converts typed rupees to integer paise without floats', () => {
    expect(rupeesTextToPaise('6000')).toBe(600000);
    expect(rupeesTextToPaise('12.5')).toBe(1250);
    expect(rupeesTextToPaise('1,27,000')).toBe(12700000);
    expect(rupeesTextToPaise('0.29')).toBe(29); // a float 0.29*100 would give 28.999...
    expect(rupeesTextToPaise('1.15')).toBe(115);
  });
  it('rejects empty, zero, negative, over-precise and non-numeric input', () => {
    for (const t of ['', '0', '0.00', '-5', '1.234', 'abc', '1e5', '1234567890']) expect(rupeesTextToPaise(t)).withContext(t).toBeNull();
  });
  it('round-trips paise to edit text', () => {
    expect(paiseToRupeesText(600000)).toBe('6000');
    expect(paiseToRupeesText(1205)).toBe('12.05');
  });
  it('formats dates without month names', () => {
    expect(shortDate('2026-10-25')).toBe('25/10/2026');
  });
  it('maps 401 to Please sign in', () => {
    expect(errorMessage(401, 'x')).toContain('Please sign in');
  });
});
