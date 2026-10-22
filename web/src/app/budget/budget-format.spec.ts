import { barWidthText, fractionToPercentText, remainingText } from './budget-format';

describe('budget format', () => {
  it('moves the decimal point by text only', () => {
    expect(fractionToPercentText('0.8500')).toBe('85');
    expect(fractionToPercentText('0.857')).toBe('85.7');
    expect(fractionToPercentText('1.25')).toBe('125');
    expect(fractionToPercentText('0.0005')).toBe('0.05');
    expect(fractionToPercentText('0')).toBe('0');
    expect(fractionToPercentText('0.1')).toBe('10');
    expect(fractionToPercentText(null)).toBeNull();
    expect(fractionToPercentText('abc')).toBeNull();
  });
  it('caps the bar at 100%', () => {
    expect(barWidthText('85')).toBe('85%');
    expect(barWidthText('100')).toBe('100%');
    expect(barWidthText('125')).toBe('100%');
    expect(barWidthText(null)).toBe('0%');
  });
  it('words the remainder', () => {
    expect(remainingText(150000)).toBe('₹1,500 left');
    expect(remainingText(-5000)).toBe('Over by ₹50');
  });
});
