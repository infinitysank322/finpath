import type { EChartsOption } from 'echarts';
import { InstalmentView } from './loan-interest.models';

export interface InterestPalette {
  text: string;
  muted: string;
  border: string;
  principal: string;
  interest: string;
}

export type MoneyFmt = (paise: number) => string;

/**
 * Stacked area, one point per remaining instalment: principal at the bottom, interest on top.
 * Colour is not the only encoding: the legend names each band, interest is dashed-outlined and
 * pattern-filled (aria decal), and the table beside the chart carries every figure. Pure: no DOM.
 */
export function interestOption(rows: InstalmentView[], pal: InterestPalette, fmt: MoneyFmt, exact: MoneyFmt = fmt): EChartsOption {
  const stack = 'payment';
  return {
    animation: false,
    aria: {
      enabled: true,
      decal: {
        show: true,
        decals: [
          { symbol: 'rect', dashArrayX: [1, 0], dashArrayY: [4, 3], rotation: 0, color: 'rgba(0,0,0,0.0)' },
          { symbol: 'rect', dashArrayX: [1, 0], dashArrayY: [2, 4], rotation: Math.PI / 4, color: pal.text },
        ],
      },
    },
    legend: { top: 0, textStyle: { color: pal.text, fontSize: 12 }, data: ['Principal', 'Interest'] },
    grid: { left: 8, right: 12, top: 36, bottom: 28, containLabel: true },
    tooltip: { trigger: 'axis', confine: true, valueFormatter: (v: unknown) => exact(Number(v)) },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: rows.map((r) => String(r.number)),
      name: 'Instalment number',
      nameLocation: 'middle',
      nameGap: 24,
      nameTextStyle: { color: pal.muted },
      axisLabel: { color: pal.muted, fontSize: 12, hideOverlap: true },
      axisLine: { lineStyle: { color: pal.border } },
    },
    yAxis: {
      type: 'value',
      min: 0,
      axisLabel: { color: pal.muted, fontSize: 12, formatter: (v: number) => fmt(v) },
      splitLine: { lineStyle: { color: pal.border } },
    },
    series: [
      {
        name: 'Principal',
        type: 'line',
        stack,
        symbol: 'none',
        data: rows.map((r) => r.principalPaise),
        lineStyle: { color: pal.principal, width: 2 },
        itemStyle: { color: pal.principal },
        areaStyle: { color: pal.principal, opacity: 0.55 },
      },
      {
        name: 'Interest',
        type: 'line',
        stack,
        symbol: 'none',
        data: rows.map((r) => r.interestPaise),
        lineStyle: { color: pal.interest, width: 2, type: 'dashed' },
        itemStyle: { color: pal.interest },
        areaStyle: { color: pal.interest, opacity: 0.55 },
      },
    ],
  };
}
