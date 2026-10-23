import { GuardStatus } from './guard.models';

/** Marker positions on the gauge, in percent. Display only; the verdict always comes from the API. */
export const WARN_LINE_PCT = 40;
export const BLOCK_LINE_PCT = 50;
/** The gauge track ends here so a ratio past the block line still has room to show. */
export const GAUGE_MAX_PCT = 100;

/** "0.4987000000" -> "49.87%". String maths only, rounded half up at 2 decimals; null ratio -> "n/a". */
export function ratioPercentText(ratio: string | null): string {
  if (ratio === null || !/^\d+(\.\d+)?$/.test(ratio)) {
    return 'n/a';
  }
  const [whole, frac = ''] = ratio.split('.');
  const f = (frac + '00000').slice(0, 5);
  let hundredths = Number(whole) * 10000 + Number(f.slice(0, 4));
  if (Number(f[4]) >= 5) {
    hundredths += 1;
  }
  return `${Math.floor(hundredths / 100)}.${String(hundredths % 100).padStart(2, '0')}%`;
}

/** Width of the filled bar, 0-100, for layout only. */
export function barPercent(ratio: string | null): number {
  if (ratio === null || !/^\d+(\.\d+)?$/.test(ratio)) {
    return 0;
  }
  return Math.min(GAUGE_MAX_PCT, Math.max(0, Number(ratio) * 100));
}

export function statusLabel(s: GuardStatus): string {
  return s === 'BLOCK' ? 'Blocked' : s === 'WARN' ? 'Warning' : 'Within limit';
}

/** Symbol + words so colour is never the only cue. */
export function statusIcon(s: GuardStatus): string {
  return s === 'BLOCK' ? '⛔' : s === 'WARN' ? '⚠' : '✓';
}

/** `adding`: a loan is being added (dialog). `current`: the user's existing EMIs (page). */
export type GuardContext = 'current' | 'adding';

export function statusExplanation(s: GuardStatus, ratioText: string, context: GuardContext = 'current'): string {
  if (ratioText === 'n/a') {
    return 'No income is recorded for this cycle, so there is no ratio to show.';
  }
  const verb = context === 'adding' ? 'would take' : 'take';
  switch (s) {
    case 'BLOCK':
      return context === 'adding'
        ? `Your EMIs would take ${ratioText} of monthly income, above the ${BLOCK_LINE_PCT}% line. Adding this loan is blocked unless you confirm.`
        : `Your EMIs take ${ratioText} of monthly income, above the ${BLOCK_LINE_PCT}% line. Adding a new loan would be blocked unless you confirm.`;
    case 'WARN':
      return `Your EMIs ${verb} ${ratioText} of monthly income, above the ${WARN_LINE_PCT}% line.`;
    default:
      return `Your EMIs ${verb} ${ratioText} of monthly income, within the ${WARN_LINE_PCT}% line.`;
  }
}
