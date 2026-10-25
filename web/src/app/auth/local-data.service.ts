import { Injectable, inject } from '@angular/core';
import { DRAFT_KEY } from '../expense/draft';
import { ExpenseQueueService, QUEUE_STORAGE, QUEUE_STORAGE_KEY, queueKeyFor } from '../expense/expense-queue.service';
import { SIGNIN_KEY, SIGNIN_STORAGE } from './signin-storage';
import { AuthSessionStore } from './session.store';

/**
 * Everything FinPath keeps on this device for a user, and how it is removed. Pure storage work: no HTTP, no
 * navigation (AuthService does those).
 */
@Injectable({ providedIn: 'root' })
export class LocalDataService {
  private readonly queue = inject(ExpenseQueueService);
  private readonly queueStorage = inject(QUEUE_STORAGE);
  private readonly tempStorage = inject(SIGNIN_STORAGE);
  private readonly session = inject(AuthSessionStore);

  /**
   * Removes the user's queue, any draft they started, the sign-in round-trip entry and the session.
   * (The one-time legacy-adoption marker is not per user and stays, so legacy items are never adopted again.)
   */
  wipeUser(userId: string | null): void {
    if (userId) {
      this.queue.forget(userId);
    }
    this.remove(this.tempStorage, DRAFT_KEY);
    this.queueStorage?.removeItem?.(QUEUE_STORAGE_KEY);
    this.remove(this.tempStorage, SIGNIN_KEY);
    this.session.clear();
    this.queue.reload();
  }

  /** Discard unsent expenses only (the "Sign out and discard" path); the session is cleared by the caller. */
  discardQueue(userId: string | null): void {
    if (userId) this.queue.forget(userId);
  }

  /**
   * Purge rule for orphaned queues: while someone is signed in, any queue key for a different user id is
   * deleted, because there is one session per device and that other user has none. It never runs with no session:
   * a user who signed out and kept their unsent items keeps them until they sign in again (or discard).
   * Returns how many queues were removed.
   */
  purgeOrphans(): number {
    const current = this.session.userId();
    const s = this.queueStorage;
    if (!current || !s?.key || !s.removeItem || typeof s.length !== 'number') return 0;
    const prefix = `${QUEUE_STORAGE_KEY}.`;
    const own = queueKeyFor(current);
    const orphans: string[] = [];
    try {
      for (let i = 0; i < s.length; i++) {
        const k = s.key(i);
        if (k && k.startsWith(prefix) && k !== own) orphans.push(k);
      }
      orphans.forEach((k) => s.removeItem!(k));
    } catch {
      return 0;
    }
    return orphans.length;
  }

  private remove(s: { removeItem(k: string): void } | null, key: string): void {
    try {
      s?.removeItem(key);
    } catch {
      /* ignore */
    }
  }
}
