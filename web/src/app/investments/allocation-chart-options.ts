import type { EChartsOption } from 'echarts';
import { AllocationSlice } from './holding-format';

export interface DonutPalette {
  text: string;
  border: string;
  /** Slice fills, cycled. Slices also carry decal patterns and text labels, so colour is never the only cue. */
  slices: string[];
}

export type MoneyFmt = (paise: number) => string;

/** Donut of the API's per-class current values. Each slice is labelled "name amount share" and patterned. Pure. */
export function allocationOption(slices: AllocationSlice[], pal: DonutPalette, fmt: MoneyFmt): EChartsOption {
  return {
    animation: false,
    aria: { enabled: true, decal: { show: true } },
    color: pal.slices,
    tooltip: { trigger: 'item', confine: true, formatter: (p: unknown) => {
      const s = slices[(p as { dataIndex: number }).dataIndex];
      return `${s.label}<br/>${fmt(s.currentValuePaise)} (${s.percent}%)`;
    } },
    series: [
      {
        type: 'pie',
        radius: ['40%', '62%'],
        center: ['50%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: pal.border, borderWidth: 2 },
        label: {
          show: true,
          color: pal.text,
          fontSize: 11,
          width: 110,
          overflow: 'break',
          formatter: (p: unknown) => {
            const s = slices[(p as { dataIndex: number }).dataIndex];
            return `${s.label}\n${fmt(s.currentValuePaise)} (${s.percent}%)`;
          },
        },
        labelLine: { show: true, lineStyle: { color: pal.text } },
        data: slices.map((s) => ({ name: s.label, value: s.currentValuePaise })),
      },
    ],
  };
}
