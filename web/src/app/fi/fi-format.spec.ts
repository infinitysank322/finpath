import { bandLabel, describeFi, fiDateText, fiRows, fractionToPercent } from './fi-format';
import { FiBandView } from './fi.models';

const band = (o: Partial<FiBandView>): FiBandView => ({ band: 'BASE', annualReturn: '0.12', requiredFirstMonthlyPaise: 5000000, projectedSavingsAtHorizonPaise: 20000000, fiYears: 10, fiDate: '2036-10-23', ...o });
const fmt = (p: number) => `P${p}`;

describe('fractionToPercent', () => {
  it('shifts the decimal point on the string', () => {
    expect(fractionToPercent('0.12')).toBe('12%');
    expect(fractionToPercent('0.0825')).toBe('8.25%');
    expect(fractionToPercent('0.06')).toBe('6%');
    expect(fractionToPercent('0')).toBe('0%');
    expect(fractionToPercent('0.10')).toBe('10%');
    expect(fractionToPercent('1')).toBe('100%');
  });
  it('returns unparseable text untouched', () => {
    expect(fractionToPercent('abc')).toBe('abc');
  });
});

describe('fi rows', () => {
  it('labels bands and keeps API paise unchanged', () => {
    expect(bandLabel('LOW')).toBe('Low');
    const [r] = fiRows([band({ band: 'HIGH', annualReturn: '0.0825' })]);
    expect(r).toEqual({ band: 'High', returnText: '8.25%', requiredSipPaise: 5000000, savingsPaise: 20000000, fiDate: '23/10/2036 (10 yrs)' });
  });
  it('says so when FI is not reached', () => {
    expect(fiDateText(band({ fiYears: null, fiDate: null }))).toContain('Not reached');
  });
  it('describes every case for screen readers', () => {
    const d = describeFi(fiRows([band({ band: 'LOW' }), band({})]), 99, 15, fmt);
    expect(d).toContain('Low case at 12% a year: P20000000 after 15 years');
    expect(d).toContain('corpus target of P99');
    expect(describeFi([], 0, 1, fmt)).toBe('No projection available.');
  });
});
