import { baselineLabel, changeText, comparisonRows, cycleLabel, cycleLastDay, describeComparison, describeDonut, donutRows, tagRows } from './spending-format';

const fmt = (p: number) => `R${p}`;
const MONTH_NAME = /January|February|March|April|May|June|July|August|September|October|November|December/;

describe('cycleLabel', () => {
  it('labels a 25th-to-25th cycle by its dates, never a month name', () => {
    const label = cycleLabel('2026-10-25', '2026-11-25');
    expect(label).toBe('25/10/2026 to 24/11/2026');
    expect(label).not.toMatch(MONTH_NAME);
  });
  it('crosses a year end by dates', () => {
    expect(cycleLabel('2026-12-25', '2027-01-25')).toBe('25/12/2026 to 24/01/2027');
  });
  it('names the month only when the cycle is a calendar month', () => {
    expect(cycleLabel('2026-10-01', '2026-11-01')).toBe('October 2026');
    expect(cycleLabel('2026-12-01', '2027-01-01')).toBe('December 2026');
  });
  it('does not name a month for a 1st-start cycle that does not end on the next 1st', () => {
    expect(cycleLabel('2026-10-01', '2026-11-15')).toBe('01/10/2026 to 14/11/2026');
  });
  it('ends a cycle on the day before the exclusive end, across a month and a leap day', () => {
    expect(cycleLastDay('2027-03-01')).toBe('2027-02-28');
    expect(cycleLastDay('2028-03-01')).toBe('2028-02-29');
  });
});

describe('baselineLabel', () => {
  it('spans first start to last end regardless of order, with no month name', () => {
    const l = baselineLabel([
      { start: '2026-09-25', endExclusive: '2026-10-25' },
      { start: '2026-07-25', endExclusive: '2026-08-25' },
      { start: '2026-08-25', endExclusive: '2026-09-25' },
    ]);
    expect(l).toBe('25/07/2026 to 24/10/2026');
    expect(l).not.toMatch(MONTH_NAME);
  });
  it('uses the cycle label for a single cycle and is empty for none', () => {
    expect(baselineLabel([{ start: '2026-09-25', endExclusive: '2026-10-25' }])).toBe('25/09/2026 to 24/10/2026');
    expect(baselineLabel([])).toBe('');
  });
});

describe('rows and descriptions', () => {
  it('maps tags in a fixed order with plain names', () => {
    const rows = tagRows({ from: 'a', toExclusive: 'b', needPaise: 3, wantPaise: 2, wastePaise: 1, untaggedPaise: 4, totalPaise: 10 });
    expect(rows.map((r) => `${r.name}:${r.paise}`)).toEqual(['Need:3', 'Want:2', 'Waste:1', 'Not tagged:4']);
  });
  it('shows the API share as a percent by string handling and keeps unknown categories by code', () => {
    const rows = donutRows([{ categoryCode: 'GROCERIES', spentPaise: 500, share: '0.2500' }, { categoryCode: 'ZZ', spentPaise: 5, share: 'bad' }]);
    expect(rows.map((r) => [r.name, r.percent])).toEqual([['Groceries', '25'], ['ZZ', null]]);
  });
  it('describes every slice and handles an empty donut', () => {
    const rows = donutRows([{ categoryCode: 'DINING', spentPaise: 1, share: '0.6' }, { categoryCode: 'FUEL', spentPaise: 1, share: '0.4' }]);
    const text = describeDonut(rows, 'R2', 'a cycle');
    expect(text).toContain('Dining 60 percent');
    expect(text).toContain('Fuel 40 percent');
    expect(describeDonut([], 'R0', 'a cycle')).toBe('No spending recorded for a cycle.');
  });
  it('words the change direction', () => {
    expect(changeText(5, fmt)).toBe('Up R5');
    expect(changeText(-5, fmt)).toBe('Down R5');
    expect(changeText(0, fmt)).toBe('No change');
  });
  it('describes the comparison with both figures and the labels', () => {
    const rows = comparisonRows([{ categoryCode: 'DINING', currentPaise: 30, averagePaise: 20, deltaPaise: 10 }]);
    const text = describeComparison(rows, 'CUR', 'BASE', fmt);
    expect(text).toContain('CUR');
    expect(text).toContain('BASE');
    expect(text).toContain('Dining: R30 against an average of R20 (up R10)');
  });
});
