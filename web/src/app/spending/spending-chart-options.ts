import type { EChartsOption } from 'echarts';
import { ComparisonRow, DonutRow, MoneyFmt } from './spending-format';

export interface SpendingPalette {
  text: string;
  muted: string;
  border: string;
  bg: string;
  slices: string[];
  current: string;
  average: string;
}

/** Donut of spend by category. Each slice is labelled with its name and the API's share, and gets an automatic pattern (decal), so colour is never the only cue. Pure: no DOM. */
export function donutOption(rows: DonutRow[], pal: SpendingPalette, fmt: MoneyFmt): EChartsOption {
  return {
    animation: false,
    aria: { enabled: true, decal: { show: true } },
    color: pal.slices,
    tooltip: { trigger: 'item', confine: true, formatter: (p: unknown) => tip(rows[(p as { dataIndex: number }).dataIndex], fmt) },
    series: [
      {
        type: 'pie',
        radius: ['38%', '58%'],
        center: ['50%', '50%'],
        minAngle: 6,
        avoidLabelOverlap: true,
        itemStyle: { borderColor: pal.bg, borderWidth: 2 },
        label: {
          show: true,
          color: pal.text,
          fontSize: 11,
          overflow: 'truncate',
          width: 72,
          formatter: (p: { dataIndex: number }) => {
            const r = rows[p.dataIndex];
            return r.percent === null ? r.name : `${r.name}\n${r.percent}%`;
          },
        },
        labelLine: { length: 6, length2: 6, lineStyle: { color: pal.muted } },
        labelLayout: { hideOverlap: false },
        data: rows.map((r) => ({ name: r.name, value: r.paise })),
      },
    ],
  };
}

const tip = (r: DonutRow, fmt: MoneyFmt): string => `${r.name}<br/>${fmt(r.paise)}${r.percent === null ? '' : ` (${r.percent}%)`}`;

/**
 * Horizontal grouped bars per category: solid = this cycle, hatched (decal) = the API's baseline average.
 * Values are the API's paise; labels carry the formatted amount so the bars read without colour. Pure: no DOM.
 */
export function comparisonOption(rows: ComparisonRow[], pal: SpendingPalette, fmt: MoneyFmt): EChartsOption {
  const cats = [...rows].reverse(); // echarts draws the first category at the bottom
  const label = { show: true, position: 'right' as const, color: pal.text, fontSize: 11, formatter: (p: { value?: unknown }) => fmt(Number(p.value)) };
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 8, right: 64, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, confine: true, valueFormatter: (v: unknown) => fmt(Number(v)) },
    xAxis: { type: 'value', min: 0, axisLabel: { show: false }, splitLine: { lineStyle: { color: pal.border } } },
    yAxis: {
      type: 'category',
      data: cats.map((r) => r.name),
      axisLabel: { color: pal.text, fontSize: 12, width: 84, overflow: 'truncate' },
      axisLine: { lineStyle: { color: pal.border } },
    },
    series: [
      {
        name: 'This cycle',
        type: 'bar',
        barGap: '10%',
        data: cats.map((r) => r.currentPaise),
        itemStyle: { color: pal.current, borderColor: pal.text, borderWidth: 1 },
        label,
      },
      {
        name: 'Average',
        type: 'bar',
        data: cats.map((r) => r.averagePaise),
        itemStyle: {
          color: pal.average,
          borderColor: pal.text,
          borderWidth: 1,
          decal: { symbol: 'rect', color: pal.text, dashArrayX: [1, 0], dashArrayY: [2, 4], rotation: Math.PI / 4 },
        },
        label,
      },
    ],
  };
}
