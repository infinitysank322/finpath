import { comparisonOption, donutOption, SpendingPalette } from './spending-chart-options';
import { comparisonRows, donutRows } from './spending-format';

const pal: SpendingPalette = { text: '#fff', muted: '#aaa', border: '#333', bg: '#111', slices: ['#1', '#2', '#3'], current: '#c8f26b', average: '#777' };
const fmt = (p: number) => `R${p}`;
type Series = { data: unknown[]; label?: { formatter: (p: unknown) => string }; itemStyle?: { decal?: unknown } };

describe('spending chart options', () => {
  it('donut: labels carry name and share, patterns are on, aria is enabled', () => {
    const rows = donutRows([{ categoryCode: 'DINING', spentPaise: 600, share: '0.6000' }, { categoryCode: 'FUEL', spentPaise: 400, share: '0.4000' }]);
    const o = donutOption(rows, pal, fmt) as { aria: { enabled: boolean; decal: { show: boolean } }; series: Series[] };
    expect(o.aria.enabled).toBeTrue();
    expect(o.aria.decal.show).toBeTrue();
    expect(o.series[0].data).toEqual([{ name: 'Dining', value: 600 }, { name: 'Fuel', value: 400 }]);
    expect(o.series[0].label!.formatter({ dataIndex: 0 })).toBe('Dining\n60%');
  });
  it('comparison: this cycle solid, average hatched, each labelled with the formatted amount', () => {
    const rows = comparisonRows([
      { categoryCode: 'DINING', currentPaise: 30, averagePaise: 20, deltaPaise: 10 },
      { categoryCode: 'FUEL', currentPaise: 5, averagePaise: 8, deltaPaise: -3 },
    ]);
    const o = comparisonOption(rows, pal, fmt) as unknown as { yAxis: { data: string[] }; series: Series[] };
    expect(o.yAxis.data).toEqual(['Fuel', 'Dining']); // reversed so the first row draws at the top
    expect(o.series[0].data).toEqual([5, 30]);
    expect(o.series[1].data).toEqual([8, 20]);
    expect(o.series[0].itemStyle!.decal).toBeUndefined();
    expect(o.series[1].itemStyle!.decal).toBeDefined();
    expect(o.series[1].label!.formatter({ value: 20 })).toBe('R20');
  });
});
