import type { EChartsOption } from 'echarts';
import { PayoffStepView, RollOffStepView } from './payoff.models';

export interface ChartPalette {
  text: string;
  muted: string;
  border: string;
  bar: string;
  step: string;
}

export type MoneyFmt = (paise: number) => string;

/**
 * Horizontal bars: one per loan in payoff order, length = cycles until it closes. Each bar carries a
 * text label (cycle + payment), so meaning never rests on colour. Pure: no DOM, no clock.
 */
export function timelineOption(order: PayoffStepView[], pal: ChartPalette, fmt: MoneyFmt): EChartsOption {
  const loans = [...order].reverse(); // echarts draws the first category at the bottom
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 8, right: 56, top: 8, bottom: 28, containLabel: true },
    tooltip: { trigger: 'item', confine: true },
    xAxis: {
      type: 'value',
      min: 0,
      minInterval: 1,
      name: 'Cycles from now',
      nameLocation: 'middle',
      nameGap: 24,
      nameTextStyle: { color: pal.muted },
      axisLabel: { color: pal.muted, fontSize: 12 },
      splitLine: { lineStyle: { color: pal.border } },
    },
    yAxis: {
      type: 'category',
      data: loans.map((l) => l.lender),
      axisLabel: { color: pal.text, fontSize: 12, width: 90, overflow: 'truncate' },
      axisLine: { lineStyle: { color: pal.border } },
    },
    series: [
      {
        type: 'bar',
        data: loans.map((l) => l.payoffCycle + 1),
        itemStyle: { color: pal.bar, borderColor: pal.text, borderWidth: 1 },
        label: {
          show: true,
          position: 'right',
          color: pal.text,
          fontSize: 12,
          formatter: (p: { dataIndex: number }) => `C${loans[p.dataIndex].payoffCycle + 1}`,
        },
        tooltip: {
          formatter: (p: { dataIndex: number }) => {
            const l = loans[p.dataIndex];
            return `${l.lender}<br/>Closes in cycle ${l.payoffCycle + 1}<br/>${fmt(l.monthlyPaymentPaise)} per cycle`;
          },
        },
      },
    ],
  };
}

/** Step line of cumulative freed EMI by cycle index, with markers and value labels at each step. */
export function rollOffOption(steps: RollOffStepView[], pal: ChartPalette, fmt: MoneyFmt): EChartsOption {
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 8, right: 16, top: 24, bottom: 28, containLabel: true },
    tooltip: { trigger: 'axis', confine: true },
    xAxis: {
      type: 'category',
      data: steps.map((s) => `C${s.cycleIndex + 1}`),
      name: 'Cycle',
      nameLocation: 'middle',
      nameGap: 24,
      nameTextStyle: { color: pal.muted },
      axisLabel: { color: pal.muted, fontSize: 12 },
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
        type: 'line',
        step: 'end',
        data: steps.map((s) => s.cumulativeFreedPaise),
        symbol: 'diamond',
        symbolSize: 9,
        lineStyle: { color: pal.step, width: 3 },
        itemStyle: { color: pal.step, borderColor: pal.text, borderWidth: 1 },
        label: { show: true, position: 'top', color: pal.text, fontSize: 11, formatter: (p: { value?: unknown }) => fmt(Number(p.value)) },
        tooltip: { valueFormatter: (v: unknown) => fmt(Number(v)) },
      },
    ],
  };
}
