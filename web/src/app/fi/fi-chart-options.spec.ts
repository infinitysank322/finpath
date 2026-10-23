import { fiOption, FiPalette } from './fi-chart-options';
import { FiRow } from './fi-format';

const pal: FiPalette = { text: '#111', muted: '#222', border: '#333', line: '#444', target: '#555' };
const rows: FiRow[] = [
  { band: 'Low', returnText: '8.25%', requiredSipPaise: 1, savingsPaise: 100, fiDate: 'x' },
  { band: 'High', returnText: '12%', requiredSipPaise: 1, savingsPaise: 300, fiDate: 'x' },
];
const fmt = (p: number) => `P${p}`;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const o = fiOption(rows, 250, pal, fmt) as any;

describe('fiOption', () => {
  it('plots only the API points, one per band', () => {
    expect(o.xAxis.data).toEqual(['Low (8.25%)', 'High (12%)']);
    expect(o.series[0].data).toEqual([100, 300]);
  });
  it('repeats the API corpus target as a dashed line', () => {
    expect(o.series[1].data).toEqual([250, 250]);
    expect(o.series[1].lineStyle.type).toBe('dashed');
  });
  it('enables aria, labels values and uses the palette', () => {
    expect(o.aria.enabled).toBeTrue();
    expect(o.series[0].label.formatter({ value: 100 })).toBe('P100');
    expect(o.series[0].lineStyle.color).toBe('#444');
    expect(o.yAxis.axisLabel.formatter(5)).toBe('P5');
  });
});
