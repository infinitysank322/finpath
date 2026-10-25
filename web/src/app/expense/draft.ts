import { KeyValueStorage } from '../auth/session.store';
import { PaymentMode, SpendTag } from './expense.models';

/** Unsaved entry kept across a sign-in round trip (same tab only). Holds no account/card numbers. */
export const DRAFT_KEY = 'finpath.expenseDraft.v1';
/** A draft older than this is dropped, whoever asks. */
export const DRAFT_TTL_MS = 10 * 60 * 1000;

export interface DraftFields {
  amountText: string;
  category: string | null;
  mode: PaymentMode | null;
  tag: SpendTag | null;
  note: string;
}

export interface Draft extends DraftFields {
  /** JWT `sub` of the sign-in that started the entry; null = typed while signed out. */
  userId: string | null;
  /** Epoch ms. */
  savedAt: number;
}

export function saveDraft(storage: KeyValueStorage | null, fields: DraftFields, userId: string | null, now = Date.now()): void {
  try {
    const d: Draft = { ...fields, userId, savedAt: now };
    storage?.setItem(DRAFT_KEY, JSON.stringify(d));
  } catch {
    /* storage blocked: the entry is lost on the round trip, nothing else breaks */
  }
}

export function clearDraft(storage: KeyValueStorage | null): void {
  try {
    storage?.removeItem(DRAFT_KEY);
  } catch {
    /* ignore */
  }
}

/** Stored draft's owner without consuming it (null if none/corrupt). `undefined` = no draft. */
export function draftOwner(storage: KeyValueStorage | null): string | null | undefined {
  try {
    const raw = storage?.getItem(DRAFT_KEY);
    if (!raw) return undefined;
    const d = JSON.parse(raw) as Partial<Draft>;
    return typeof d.userId === 'string' ? d.userId : null;
  } catch {
    return undefined;
  }
}

/**
 * Reads AND removes the draft. Returned only when it is fresh (<= DRAFT_TTL_MS) and was started by `currentUser`
 * (or typed while signed out, in which case whoever signs in within the TTL gets it). Nobody signed in: not
 * returned (and left to expire). A draft from another user is deleted unread.
 */
export function takeDraft(storage: KeyValueStorage | null, currentUser: string | null, now = Date.now()): Draft | null {
  if (!currentUser) return null;
  let raw: string | null = null;
  try {
    raw = storage?.getItem(DRAFT_KEY) ?? null;
    if (raw) storage?.removeItem(DRAFT_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const d = JSON.parse(raw) as Partial<Draft>;
    if (typeof d.savedAt !== 'number' || now - d.savedAt > DRAFT_TTL_MS || d.savedAt > now + 60_000) return null;
    if (d.userId !== null && d.userId !== currentUser) return null;
    if (d.userId === undefined) return null;
    return d as Draft;
  } catch {
    return null;
  }
}
