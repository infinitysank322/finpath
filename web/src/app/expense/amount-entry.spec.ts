import { formatExactPaise, formatTyped, pressKey, toPaise } from './amount-entry';

describe('amount entry', () => {
  const type = (s: string) => [...s].reduce((t, k) => pressKey(t, k), '');

  it('converts rupees text to integer paise without floats', () => {
    expect(toPaise('')).toBe(0);
    expect(toPaise('250')).toBe(25000);
    expect(toPaise('12.5')).toBe(1250);
    expect(toPaise('12.05')).toBe(1205);
    expect(toPaise('0.')).toBe(0);
    expect(toPaise('19.99')).toBe(1999);
    expect(Number.isInteger(toPaise('1234567.89'))).toBeTrue();
  });

  it('rejects leading zeros, second decimal point, third decimal and absurd length', () => {
    expect(type('007')).toBe('7');
    expect(type('1.2.3')).toBe('1.23');
    expect(type('1.234')).toBe('1.23');
    expect(type('.5')).toBe('0.5');
    expect(type('12345678901').length).toBe(9);
  });

  it('backspace removes the last character', () => {
    expect(pressKey('12.5', 'back')).toBe('12.');
    expect(pressKey('', 'back')).toBe('');
  });

  it('formats exact paise and en-IN grouping with integers', () => {
    expect(formatExactPaise(1250)).toBe('₹12.50');
    expect(formatExactPaise(40)).toBe('₹0.40');
    expect(formatExactPaise(25000)).toBe('₹250');
    expect(formatExactPaise(123456789)).toBe('₹12,34,567.89');
    expect(formatExactPaise(0)).toBe('₹0');
    expect(formatTyped('')).toBe('0');
    expect(formatTyped('100000')).toBe('1,00,000');
    expect(formatTyped('12.')).toBe('12.');
  });
});
