/** Typed rupees -> integer paise, zero allowed (a goal may start at nothing saved). Null when malformed. No floats. */
export function rupeesTextToPaiseOrZero(text: string): number | null {
  const m = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(text.trim().replace(/,/g, ''));
  if (!m) return null;
  return parseInt(m[1], 10) * 100 + (m[2] ? parseInt(m[2].padEnd(2, '0'), 10) : 0);
}

/**
 * Display-only width for the progress bar, whole percent clamped to 0..100. The status, monthly need and
 * on-track flag all come from the API; this only draws "how much of the target is already saved".
 */
export function progressPercent(currentPaise: number, targetPaise: number): number {
  if (targetPaise <= 0 || currentPaise <= 0) return 0;
  return Math.min(100, Math.floor((currentPaise * 100) / targetPaise));
}
