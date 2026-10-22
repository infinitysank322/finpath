/** Keypad text handling. Typed rupees become integer paise by digit/string handling only; no floats. */
const MAX_RUPEE_DIGITS = 9;

export function pressKey(current: string, key: string): string {
  if (key === 'back') return current.slice(0, -1);
  if (key === '.') return current.includes('.') ? current : (current === '' ? '0.' : current + '.');
  if (!/^[0-9]$/.test(key)) return current;
  const [rupees, paise] = current.split('.');
  if (paise !== undefined) return paise.length >= 2 ? current : current + key;
  if (rupees === '0') return key; // no leading zeros
  return rupees.length >= MAX_RUPEE_DIGITS ? current : current + key;
}

/** "250" -> 25000, "12.5" -> 1250, "" or "." -> 0. Result is an integer. */
export function toPaise(text: string): number {
  const [rupees = '', frac = ''] = text.split('.');
  const whole = rupees === '' ? 0 : parseInt(rupees, 10);
  const fraction = frac === '' ? 0 : parseInt(frac.padEnd(2, '0'), 10);
  return whole * 100 + fraction;
}

/** Lakh-style grouping of a string of digits: "1234567" -> "12,34,567". String handling only. */
export function groupIndian(digits: string): string {
  if (digits.length <= 3) return digits;
  const head = digits.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',');
  return `${head},${digits.slice(-3)}`;
}

/** Typed keypad text grouped en-IN while keeping the typed fraction ("1234567.5" -> "12,34,567.5", "12." stays). */
export function formatTyped(text: string): string {
  if (text === '') return '0';
  const [rupees, frac] = text.split('.');
  const whole = groupIndian(rupees === '' ? '0' : rupees);
  return frac === undefined ? whole : `${whole}.${frac}`;
}

/** Exact integer-paise display: 1250 -> "₹12.50", 40 -> "₹0.40", 25000 -> "₹250". Never rounds. */
export function formatExactPaise(paise: number): string {
  const rupees = Math.floor(paise / 100);
  const rem = paise % 100;
  const whole = groupIndian(String(rupees));
  return rem === 0 ? `₹${whole}` : `₹${whole}.${String(rem).padStart(2, '0')}`;
}
