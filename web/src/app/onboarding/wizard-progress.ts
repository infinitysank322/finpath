import { KeyValueStorage } from '../auth/session.store';

export const WIZARD_KEY_PREFIX = 'finpath.onboarding.v1';

export const wizardKeyFor = (userId: string) => `${WIZARD_KEY_PREFIX}.${userId}`;

/** Resume position only: a step id and index. Never holds amounts or personal data. */
export function loadPosition(storage: KeyValueStorage | null, userId: string | null): { id: string; index: number } | null {
  if (!storage || !userId) return null;
  try {
    const raw = storage.getItem(wizardKeyFor(userId));
    if (!raw) return null;
    const o = JSON.parse(raw) as { id?: unknown; index?: unknown };
    if (typeof o.id !== 'string' || typeof o.index !== 'number' || !Number.isInteger(o.index) || o.index < 0) return null;
    return { id: o.id, index: o.index };
  } catch {
    return null;
  }
}

export function savePosition(storage: KeyValueStorage | null, userId: string | null, id: string, index: number): void {
  if (!storage || !userId) return;
  try {
    storage.setItem(wizardKeyFor(userId), JSON.stringify({ id, index }));
  } catch {
    /* blocked or full: the wizard still works, it just will not resume */
  }
}

export function clearPosition(storage: KeyValueStorage | null, userId: string | null): void {
  if (!storage || !userId) return;
  try {
    storage.removeItem(wizardKeyFor(userId));
  } catch {
    /* ignore */
  }
}
