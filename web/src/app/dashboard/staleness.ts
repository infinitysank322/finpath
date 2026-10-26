/** Plain-words age of cached data, e.g. "3 hours ago". Pure; `now` is injected. */
export function ageLabel(savedAt: number, now: number): string {
  const mins = Math.max(0, Math.floor((now - savedAt) / 60_000));
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} ${mins === 1 ? 'minute' : 'minutes'} ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} ${hours === 1 ? 'hour' : 'hours'} ago`;
  const days = Math.floor(hours / 24);
  return `${days} ${days === 1 ? 'day' : 'days'} ago`;
}
