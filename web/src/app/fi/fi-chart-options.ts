import type { EChartsOption } from 'echarts';
import { FiRow } from './fi-format';

export interface FiPalette {
  text: string;
  muted: string;
  border: string;
  line: string;
  target: string;
}

export type MoneyFmt = (paise: number) => string;

/**
 * Line through the API's low/base/high projected savings at the horizon, against the API's corpus target
 * drawn as a dashed flat line. Only API-provided points are plotted: no interpolated series. Values are
 * labelled and the target line is dashed, so meaning never rests on colour alone. Pure: no DOM.
 */
export function fiOption(rows: FiRow[], netCorpusPaise: number, pal: FiPalette, fmt: MoneyFmt): EChartsOption {
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 8, right: 16, top: 28, bottom: 28, containLabel: true },
    tooltip: { trigger: 'axis', confine: true, valueFormatter: (v: unknown) => fmt(Number(v)) },
    legend: { top: 0, textStyle: { color: pal.text, fontSize: 12 } },
    xAxis: {
      type: 'category',
      data: rows.map((r) => `${r.band} (${r.returnText})`),
      axisLabel: { color: pal.muted, fontSize: 12 },
      axisLine: { lineStyle: { color: pal.border } },
    },
    yAxis: {
      type: 'value',
      min: 0,
      axisLabel: { color: pal.muted, fontSize: 11, formatter: (v: number) => fmt(v) },
      splitLine: { lineStyle: { color: pal.border } },
    },
    series: [
      {
        name: 'Projected savings',
        type: 'line',
        data: rows.map((r) => r.savingsPaise),
        symbol: 'circle',
        symbolSize: 9,
        lineStyle: { color: pal.line, width: 3 },
        itemStyle: { color: pal.line, borderColor: pal.text, borderWidth: 1 },
        label: { show: true, position: 'top', color: pal.text, fontSize: 11, formatter: (p: { value?: unknown }) => fmt(Number(p.value)) },
      },
      {
        name: 'Corpus target',
        type: 'line',
        data: rows.map(() => netCorpusPaise),
        symbol: 'none',
        lineStyle: { color: pal.target, width: 2, type: 'dashed' },
        itemStyle: { color: pal.target },
      },
    ],
  };
}
