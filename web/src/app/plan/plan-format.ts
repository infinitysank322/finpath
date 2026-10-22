/**
 * Typed rupees -> integer paise by string/integer handling only. Accepts "250", "12.5", "12.50";
 * returns null for empty, zero, malformed or over-long input. No floats.
 */
export function rupeesTextToPaise(text: string): number | null {
  const t = text.trim().replace(/,/g, '');
  const m = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(t);
  if (!m) return null;
  const paise = parseInt(m[1], 10) * 100 + (m[2] ? parseInt(m[2].padEnd(2, '0'), 10) : 0);
  return paise > 0 ? paise : null;
}

/** 25000 -> "250", 1250 -> "12.50" for prefilling the edit form. Integer arithmetic only. */
export function paiseToRupeesText(paise: number): string {
  const rem = paise % 100;
  const whole = (paise - rem) / 100;
  return rem === 0 ? String(whole) : `${whole}.${String(rem).padStart(2, '0')}`;
}

/** "2026-10-25" -> "25/10/2026" (no month names: the cycle is user-defined). */
export function shortDate(iso: string): string {
  const [y, m, d] = iso.split('-');
  return `${d}/${m}/${y}`;
}

/** Message for a failed call. 401 is the "Please sign in" case until sign-in (T33) exists. */
export function errorMessage(status: number, action: string): string {
  switch (status) {
    case 401:
      return 'Please sign in to continue.';
    case 403:
      return 'Consent is needed before this can be saved.';
    case 400:
      return `${action} was rejected. Check the amount and dates.`;
    case 404:
      return 'This item no longer exists. Reload the list.';
    case 0:
      return 'You appear to be offline. Try again when you are connected.';
    default:
      return status >= 500 ? 'The server had a problem. Please try again in a moment.' : 'Something went wrong. Please try again.';
  }
}
