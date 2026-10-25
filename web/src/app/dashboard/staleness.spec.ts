import { ageLabel } from './staleness';

describe('ageLabel', () => {
  const now = 10_000_000_000;
  it('words minutes, hours and days', () => {
    expect(ageLabel(now - 20_000, now)).toBe('just now');
    expect(ageLabel(now - 60_000, now)).toBe('1 minute ago');
    expect(ageLabel(now - 5 * 60_000, now)).toBe('5 minutes ago');
    expect(ageLabel(now - 3 * 3_600_000, now)).toBe('3 hours ago');
    expect(ageLabel(now - 48 * 3_600_000, now)).toBe('2 days ago');
  });
  it('treats a future timestamp as just now', () => {
    expect(ageLabel(now + 60_000, now)).toBe('just now');
  });
});
