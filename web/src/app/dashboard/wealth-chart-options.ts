import type { EChartsOption } from 'echarts';
import { FiPalette, MoneyFmt } from '../fi/fi-chart-options';
import { NetWorthSection } from './dashboard.models';

/** Net-worth line through the API's snapshot points, one per closed cycle. No interpolation, no derived series. Pure: no DOM. */
export function netWorthOption(series: NetWorthSection['series'], label: (iso: string) => string, pal: FiPalette, fmt: MoneyFmt): EChartsOption {
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 8, right: 16, top: 16, bottom: 24, containLabel: true },
    tooltip: { trigger: 'axis', confine: true, valueFormatter: (v: unknown) => fmt(Number(v)) },
    xAxis: {
      type: 'category',
      data: series.map((p) => label(p.cycleStart)),
      axisLabel: { color: pal.muted, fontSize: 11 },
      axisLine: { lineStyle: { color: pal.border } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: pal.muted, fontSize: 11, formatter: (v: number) => fmt(v) },
      splitLine: { lineStyle: { color: pal.border } },
    },
    series: [{
      name: 'Net worth',
      type: 'line',
      data: series.map((p) => p.netWorthPaise),
      symbol: 'circle',
      symbolSize: 8,
      lineStyle: { color: pal.line, width: 3 },
      itemStyle: { color: pal.line, borderColor: pal.text, borderWidth: 1 },
    }],
  };
}
